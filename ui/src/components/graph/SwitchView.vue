<template>
    <el-button-group size="small">
        <ValidationError :error="flowError" />
        <el-tooltip :content="$t('source')" transition="" :hide-after="0" :persistent="false">
            <el-button :type="buttonType('source')" @click="switchView('source')" :icon="FileDocumentEdit" />
        </el-tooltip>
        <el-tooltip :content="$t('source and topology')" transition="" :hide-after="0" :persistent="false">
            <el-button :type="buttonType('combined')" @click="switchView('combined')" :icon="FileChart" />
        </el-tooltip>
        <el-tooltip :content="$t('topology')" transition="" :hide-after="0" :persistent="false">
            <el-button :type="buttonType('topology')" @click="switchView('topology')" :icon="Graph" />
        </el-tooltip>
    </el-button-group>
</template>

<script setup>
    import FileDocumentEdit from "vue-material-design-icons/FileDocumentEdit.vue";
    import Graph from "vue-material-design-icons/Graph.vue";
    import FileChart from "vue-material-design-icons/FileChart.vue";
    import CloseCircleOutline from "vue-material-design-icons/CloseCircleOutline.vue";
    import CheckCircleOutline from "vue-material-design-icons/CheckCircleOutline.vue";
</script>

<script>
    import {mapGetters} from "vuex";
    import ValidationError from "../flows/ValidationError.vue";

    export default {

        components: {ValidationError},
        props: {
            type: {
                type: String
            }
        },
        computed: {
            ...mapGetters("flow", ["flowError"]),
        },
        methods: {
            switchView(view) {
                this.$emit("switch-view", view)
            },
            buttonType(view) {
                return view === this.type ? "primary" : "default";
            }
        }
    }
</script>
