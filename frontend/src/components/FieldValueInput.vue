<script setup lang="ts">
import { computed } from 'vue'
import type { StudentField } from '../api'
import { fieldOptions } from '../field-state'
const props = defineProps<{ field: StudentField; modelValue: unknown; disabled?: boolean; invalid?: boolean }>()
const options = computed(() => fieldOptions(props.field))
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
function update(event: Event) { emit('update:modelValue', (event.target as HTMLInputElement).value) }
</script>
<template>
  <select v-if="options.length" :aria-label="field.label" :value="modelValue == null ? '' : String(modelValue)" class="form-input dynamic-input" :class="{ invalid }" :disabled="disabled" @change="update"><option value="">请选择{{ field.label }}</option><option v-if="modelValue != null && String(modelValue) && !options.includes(String(modelValue))" :value="String(modelValue)" disabled>原值：{{ modelValue }}（不符合规则）</option><option v-for="option in options" :key="option" :value="option">{{ option }}</option></select>
  <select v-else-if="field.type === 'BOOLEAN'" :aria-label="field.label" :value="modelValue == null ? '' : String(modelValue)" class="form-input dynamic-input" :class="{ invalid }" :disabled="disabled" @change="update"><option value="">请选择</option><option value="true">是</option><option value="false">否</option></select>
  <input v-else :aria-label="field.label" :value="modelValue == null ? '' : String(modelValue)" :type="field.type === 'DATE' ? 'date' : 'text'" :inputmode="['INTEGER','DECIMAL'].includes(field.type) ? 'decimal' : 'text'" :maxlength="field.type === 'TEXT' ? 255 : undefined" class="form-input dynamic-input" :class="{ invalid }" :disabled="disabled" :placeholder="field.type === 'DATE' ? 'yyyy-MM-dd' : field.label" @input="update" />
</template>
