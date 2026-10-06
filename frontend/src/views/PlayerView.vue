<script setup>
import { onMounted, ref } from 'vue'
import api from '../api'

const rows = ref([])
const error = ref('')
const tip = ref('')
const loading = ref(false)
const dialog = ref(false)
const saving = ref(false)

const form = ref(blank())
function blank() {
  return { playerNo: '', name: '', handicap: 10, phone: '', note: '' }
}

async function load() {
  loading.value = true; error.value = ''
  try {
    rows.value = (await api.listPlayers()) || []
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

function openCreate() { form.value = blank(); dialog.value = true; tip.value = ''; error.value = '' }
async function save() {
  saving.value = true; error.value = ''
  try {
    const d = { ...form.value, handicap: Number(form.value.handicap) }
    await api.createPlayer(d)
    tip.value = `球员 ${d.name} 已建档`
    dialog.value = false
    await load()
  } catch (e) { error.value = e.message } finally { saving.value = false }
}

function statusType(h) {
  if (h <= 5) return 'success'
  if (h <= 18) return 'warning'
  return 'info'
}

onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">球员会馆 · 球员名册</span>
        <span class="panel-desc">差点 handicap 必须落在 0 ~ 54 之间（USGA 上限）</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">球员数</div><div class="v">{{ rows.length }}</div></div>
        <div class="stat"><div class="k">单差点(≤5)</div><div class="v">{{ rows.filter(r => r.handicap <= 5).length }}</div></div>
        <div class="stat"><div class="k">中差点(6~18)</div><div class="v">{{ rows.filter(r => r.handicap > 5 && r.handicap <= 18).length }}</div></div>
        <div class="stat"><div class="k">高差点(>18)</div><div class="v">{{ rows.filter(r => r.handicap > 18).length }}</div></div>
      </div>

      <div class="toolbar">
        <span class="spacer"></span>
        <el-button type="primary" @click="openCreate">新建球员</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="playerNo" label="球员编号" width="120" />
        <el-table-column prop="name" label="姓名" min-width="120" />
        <el-table-column prop="handicap" label="差点" width="120">
          <template #default="{ row }">
            <el-tag :type="statusType(row.handicap)">{{ row.handicap }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="phone" label="联系电话" width="140" />
        <el-table-column prop="note" label="备注" min-width="140" />
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="新建球员" width="560px">
      <el-form :model="form" label-width="110px" class="dialog-form">
        <el-form-item label="球员编号"><el-input v-model="form.playerNo" placeholder="如 P-011" /></el-form-item>
        <el-form-item label="姓名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="差点(0~54)"><el-input v-model="form.handicap" type="number" min="0" max="54" /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="form.phone" /></el-form-item>
        <el-form-item label="备注" class="full"><el-input v-model="form.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">建档</el-button>
      </template>
    </el-dialog>
  </div>
</template>
