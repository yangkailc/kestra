package io.kestra.core.docs;

import com.github.jknack.handlebars.EscapingStrategy;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.github.jknack.handlebars.helper.*;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import io.kestra.core.models.conditions.Condition;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.plugins.RegisteredPlugin;
import io.kestra.core.runners.handlebars.helpers.OtherBooleansHelper;
import io.kestra.core.runners.handlebars.helpers.DateHelper;
import io.kestra.core.runners.handlebars.helpers.JsonHelper;
import io.kestra.core.runners.handlebars.helpers.OtherStringsHelper;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.Slugify;
import lombok.SneakyThrows;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import static io.kestra.core.utils.Rethrow.throwFunction;

@Singleton
public class DocumentationGenerator {
    private static final Handlebars handlebars = new Handlebars()
        .with(EscapingStrategy.NOOP)
        .registerHelpers(ConditionalHelpers.class)
        .registerHelpers(EachHelper.class)
        .registerHelpers(LogHelper.class)
        .registerHelpers(StringHelpers.class)
        .registerHelpers(OtherStringsHelper.class)
        .registerHelpers(OtherBooleansHelper.class)
        .registerHelper("definitionName", (context, options) -> {
            String s = StringUtils.substringAfterLast(context.toString(), ".");
            if (s.contains("-")) {
                String s1 = StringUtils.substringAfter(s, "-");

                try {
                    Integer.parseInt(s1);
                } catch (NumberFormatException e) {
                    s = s1;
                }
            }
            return s;
        })
        .registerHelpers(UnlessHelper.class)
        .registerHelpers(WithHelper.class)
        .registerHelpers(DateHelper.class)
        .registerHelpers(JsonHelper.class);

    @Inject
    JsonSchemaGenerator jsonSchemaGenerator;

    public List<Document> generate(RegisteredPlugin registeredPlugin) throws IOException, URISyntaxException {
        ArrayList<Document> result = new ArrayList<>();

        result.addAll(index(registeredPlugin));

        result.addAll(this.generate(registeredPlugin, registeredPlugin.getTasks(), Task.class, "tasks"));
        result.addAll(this.generate(registeredPlugin, registeredPlugin.getTriggers(), AbstractTrigger.class, "triggers"));
        result.addAll(this.generate(registeredPlugin, registeredPlugin.getConditions(), Condition.class, "conditions"));

        result.addAll(guides(registeredPlugin));

        return result;
    }

    private static List<Document> index(RegisteredPlugin plugin) throws IOException {
        PluginDocumentation pluginDocumentation = PluginDocumentation.of(plugin);

        if (pluginDocumentation.getClassPlugins().size() == 0) {
            return Collections.emptyList();
        }

        ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();

        if (plugin.getManifest() != null) {
            builder.put("title", plugin.getManifest().getMainAttributes().getValue("X-Kestra-Title"));

            if (plugin.getManifest().getMainAttributes().getValue("X-Kestra-Description") != null) {
                builder.put("description", plugin.getManifest().getMainAttributes().getValue("X-Kestra-Description"));
            }

            var group = plugin.getManifest().getMainAttributes().getValue("X-Kestra-Group");
            builder.put("group", group);
            longDescription(plugin, builder, group);
            builder.put("classPlugins", pluginDocumentation.getClassPlugins());
        }

        if (pluginDocumentation.getIcon() != null) {
            builder.put("icon", pluginDocumentation.getIcon());
        }

        if(!plugin.getGuides().isEmpty()) {
            builder.put("guides", plugin.getGuides());
        }

        return Collections.singletonList(new Document(
            docPath(plugin),
            render("index", builder.build()),
            pluginDocumentation.getIcon()
        ));
    }

    private static List<Document> guides(RegisteredPlugin plugin) throws IOException, URISyntaxException {
        String pluginName = Slugify.of(plugin.title());

        var guides = plugin.getClassLoader().getResource("doc/guides");
        if(guides != null) {
            try (var fileSystem = FileSystems.newFileSystem(guides.toURI(), Collections.emptyMap())) {
                var root = fileSystem.getPath("/doc/guides");
                try (var stream = Files.walk(root, 1)) {
                    return stream
                        .skip(1) // first element is the root element
                        .map(throwFunction(path -> new Document(
                            pluginName + "/guides/" + path.getName(path.getParent().getNameCount()),
                            new String(Files.readAllBytes(path)),
                            null
                            ))
                        )
                        .collect(Collectors.toList());
                }
            }
        }

        return Collections.emptyList();
    }

    private static void longDescription(RegisteredPlugin plugin, ImmutableMap.Builder<String, Object> builder, String group) {
        try (var is = plugin.getClassLoader().getResourceAsStream("doc/" + group + ".md")) {
            if(is != null) {
                builder.put("longDescription", IOUtils.toString(is, Charsets.UTF_8));
            }
        }
        catch (Exception e) {
            // silently fail
        }
    }

    private <T> List<Document> generate(RegisteredPlugin registeredPlugin, List<Class<? extends T>> cls, Class<T> baseCls, String type) {
        return cls
            .stream()
            .map(r -> ClassPluginDocumentation.of(jsonSchemaGenerator, registeredPlugin, r, baseCls))
            .map(pluginDocumentation -> {
                try {
                    return new Document(
                        docPath(registeredPlugin, type, pluginDocumentation),
                        render(pluginDocumentation),
                        pluginDocumentation.getIcon()
                    );
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            })
            .collect(Collectors.toList());
    }

    @SneakyThrows
    public static String icon(RegisteredPlugin plugin, Class<?> cls ) {
        InputStream resourceAsStream = Stream
            .of(
                plugin.getClassLoader().getResourceAsStream("icons/" + cls.getName() + ".svg"),
                plugin.getClassLoader().getResourceAsStream("icons/" + cls.getPackageName() + ".svg")
            )
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);

        if (resourceAsStream != null) {
            return Base64.getEncoder().encodeToString(
                IOUtils.toString(resourceAsStream, Charsets.UTF_8).getBytes(StandardCharsets.UTF_8)
            );
        }

        return null;
    }

    @SneakyThrows
    public static String icon(RegisteredPlugin plugin, String iconName) {
        InputStream resourceAsStream = plugin.getClassLoader().getResourceAsStream("icons/" + iconName + ".svg");
        if (resourceAsStream != null) {
            return Base64.getEncoder().encodeToString(
                IOUtils.toString(resourceAsStream, Charsets.UTF_8).getBytes(StandardCharsets.UTF_8)
            );
        }

        return null;
    }

    private static <T> String docPath(RegisteredPlugin registeredPlugin) {
        String pluginName = Slugify.of(registeredPlugin.title());

        return pluginName + "/README.md";
    }

    private static <T> String docPath(RegisteredPlugin registeredPlugin, String type, ClassPluginDocumentation<T> classPluginDocumentation) {
        String pluginName = Slugify.of(registeredPlugin.title());

        return pluginName + "/" + type + "/" +
            (classPluginDocumentation.getSubGroup() != null ? classPluginDocumentation.getSubGroup() + "/" : "") +
            classPluginDocumentation.getCls() + ".md";
    }

    public static <T> String render(ClassPluginDocumentation<T> classPluginDocumentation) throws IOException {
        return render("task", JacksonMapper.toMap(classPluginDocumentation));
    }

    public static <T> String render(String templateName, Map<String, Object> vars) throws IOException {
        String hbsTemplate = IOUtils.toString(
            Objects.requireNonNull(DocumentationGenerator.class.getClassLoader().getResourceAsStream("docs/" + templateName + ".hbs")),
            Charsets.UTF_8
        );

        Template template = handlebars.compileInline(hbsTemplate);
        String renderer = template.apply(vars);

        // vuepress {{ }} evaluation
        Pattern pattern = Pattern.compile("`\\{\\{(.*?)\\}\\}`", Pattern.MULTILINE);
        renderer = pattern.matcher(renderer).replaceAll("<code v-pre>{{ $1 }}</code>");

        return renderer;
    }
}
