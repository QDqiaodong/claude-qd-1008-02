<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
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

// 差点指数详情抽屉
const detail = ref(null)
const detailLoading = ref(false)
const detailPlayer = ref(null)
const showOldChain = ref(false)
const oldChain = ref([])
const ratings = ref([])
const bindRating = ref({})
const binding = ref(false)

async function loadRatings() {
  if (ratings.value.length === 0) {
    try { ratings.value = await api.listRatings() } catch { /* 无管理员数据时忽略 */ }
  }
}
async function bindAndRecalc(round) {
  const ratingId = bindRating.value[round.scorecardId]
  if (!ratingId) { ElMessage.warning('请先选择梯台'); return }
  binding.value = true
  try {
    await api.bindRoundMeta(round.scorecardId, { courseRatingId: ratingId })
    ElMessage.success('已补绑梯台，正在定向重算')
    const batch = await api.targetRecalc({ playerId: detailPlayer.value.id })
    setTimeout(async () => {
      detail.value = await api.handicapProfile(detailPlayer.value.id)
      rows.value = await api.listPlayers()
    }, 1500)
  } catch (e) { ElMessage.error(e.message) } finally { binding.value = false }
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

async function openDetail(row) {
  detailPlayer.value = row
  detail.value = null
  detailLoading.value = true
  showOldChain.value = false
  oldChain.value = []
  bindRating.value = {}
  loadRatings()
  try {
    detail.value = await api.handicapProfile(row.id)
  } catch (e) { ElMessage.error(e.message) } finally { detailLoading.value = false }
}
async function toggleOldChain() {
  showOldChain.value = !showOldChain.value
  if (showOldChain.value && oldChain.value.length === 0) {
    oldChain.value = await api.handicapHistory(detailPlayer.value.id)
  }
}
async function recalcPlayer() {
  try {
    const batch = await api.targetRecalc({ playerId: detailPlayer.value.id })
    ElMessage.success(`定向重算批次 ${batch.batchNo} 已提交`)
    setTimeout(async () => {
      detail.value = await api.handicapProfile(detailPlayer.value.id)
      rows.value = await api.listPlayers()
    }, 1500)
  } catch (e) { ElMessage.error(e.message) }
}

function statusType(h) {
  if (h <= 5) return 'success'
  if (h <= 18) return 'warning'
  return 'info'
}
const indexTag = (s) => ({ CALCULATED: 'success', INSUFFICIENT: 'warning', NO_RULE: 'info' }[s] || 'info')
const indexStatusText = (s) => ({ CALCULATED: '已算出', INSUFFICIENT: '样本不足', NO_RULE: '无生效规则' }[s] || s || '未计算')
function changeText(c) {
  if (c == null) return '—'
  const n = Number(c)
  return (n > 0 ? '+' : '') + n.toFixed(1)
}
function changeType(c) {
  if (c == null) return 'info'
  return Number(c) <= 0 ? 'success' : 'danger'
}
const triggerText = (t) => ({
  SIGNOFF: '签核', TARGET_RECALC: '定向重算', FULL_RECALC: '全量重算', BOOTSTRAP: '启动回填'
}[t] || t)

onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">球员会馆 · 球员名册</span>
        <span class="panel-desc">差点 handicap 为建档人工值（0~54）；差点指数由最近二十轮成绩按生效规则重算，点「指数详情」看计算依据</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">球员数</div><div class="v">{{ rows.length }}</div></div>
        <div class="stat"><div class="k">已出指数</div>
          <div class="v">{{ rows.filter(r => r.indexStatus === 'CALCULATED').length }}</div></div>
        <div class="stat"><div class="k">样本不足</div>
          <div class="v">{{ rows.filter(r => r.indexStatus === 'INSUFFICIENT').length }}</div></div>
        <div class="stat"><div class="k">单差点(指数≤5)</div>
          <div class="v">{{ rows.filter(r => r.handicapIndex != null && r.handicapIndex <= 5).length }}</div></div>
      </div>

      <div class="toolbar">
        <span class="spacer"></span>
        <el-button type="primary" @click="openCreate">新建球员</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="playerNo" label="球员编号" width="110" />
        <el-table-column prop="name" label="姓名" min-width="100" />
        <el-table-column label="差点指数" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.handicapIndex != null && row.indexStatus === 'CALCULATED'"
                    :type="row.handicapIndex <= 5 ? 'success' : 'warning'">
              {{ Number(row.handicapIndex).toFixed(1) }}
            </el-tag>
            <el-tag v-else type="info">{{ indexStatusText(row.indexStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="指数截止日" width="110">
          <template #default="{ row }">{{ row.indexAsOf || '—' }}</template>
        </el-table-column>
        <el-table-column prop="handicap" label="建档差点" width="90" />
        <el-table-column prop="phone" label="联系电话" width="130" />
        <el-table-column prop="note" label="备注" min-width="120" />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">指数详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="新建球员" width="560px">
      <el-form :model="form" label-width="110px" class="dialog-form">
        <el-form-item label="球员编号"><el-input v-model="form.playerNo" placeholder="如 P-011" /></el-form-item>
        <el-form-item label="姓名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="建档差点(0~54)"><el-input v-model="form.handicap" type="number" min="0" max="54" />
          <span class="form-hint">人工初始值；指数由系统重算</span>
        </el-form-item>
        <el-form-item label="联系电话"><el-input v-model="form.phone" /></el-form-item>
        <el-form-item label="备注" class="full"><el-input v-model="form.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">建档</el-button>
      </template>
    </el-dialog>

    <!-- 差点指数详情抽屉 -->
    <el-drawer v-model="detail" :title="`差点指数详情 · ${detailPlayer?.name || ''}`" size="62%"
               :destroy-on-close="false">
      <div v-if="detail" v-loading="detailLoading" class="hc-detail">
        <!-- 当前指数 + 最近变化 + 规则 -->
        <div class="hc-top">
          <div class="hc-card">
            <div class="hc-k">当前指数</div>
            <div class="hc-v">
              <template v-if="detail.currentIndex != null && detail.indexStatus === 'CALCULATED'">
                {{ Number(detail.currentIndex).toFixed(1) }}
              </template>
              <template v-else>
                <el-tag :type="indexTag(detail.indexStatus)">{{ indexStatusText(detail.indexStatus) }}</el-tag>
              </template>
            </div>
            <div class="hc-sub">截止 {{ detail.asOfDate || '—' }}</div>
          </div>
          <div class="hc-card">
            <div class="hc-k">最近变化</div>
            <div class="hc-v">
              <el-tag :type="changeType(detail.lastChange)">{{ changeText(detail.lastChange) }}</el-tag>
            </div>
            <div class="hc-sub">上一节点 {{ detail.previousIndex != null ? Number(detail.previousIndex).toFixed(1) : '—' }}</div>
          </div>
          <div class="hc-card hc-card-wide">
            <div class="hc-k">采用规则</div>
            <div class="hc-v hc-rule">
              {{ detail.ruleName || '无生效规则' }}
              <el-tag size="small" type="info" v-if="detail.ruleEffectiveDate">
                生效 {{ detail.ruleEffectiveDate }}
              </el-tag>
            </div>
            <div class="hc-sub" v-if="detail.cappedNote">{{ detail.cappedNote }}</div>
          </div>
        </div>

        <!-- 缺参数提示 + 补绑梯台 -->
        <template v-if="detail.missingRounds && detail.missingRounds.length">
          <el-alert type="warning" :closable="false" show-icon class="hc-alert"
                    title="以下已签核轮次缺球场难度参数（梯台），未计入指数；补齐参数后可直接在下方补绑并定向重算。">
          </el-alert>
          <el-table :data="detail.missingRounds" border size="small">
            <el-table-column prop="playDate" label="完赛日" width="100" />
            <el-table-column prop="tournamentName" label="赛事" min-width="160" show-overflow-tooltip />
            <el-table-column prop="roundNo" label="轮" width="50" />
            <el-table-column prop="totalStrokes" label="总杆" width="60" />
            <el-table-column label="补绑梯台" min-width="220">
              <template #default="{ row }">
                <el-select v-model="bindRating[row.scorecardId]" placeholder="选择梯台"
                           size="small" style="width: 100%">
                  <el-option v-for="r in ratings" :key="r.id"
                             :label="`${r.courseName} · ${r.teeName}（${r.courseRatingValue}/${r.slopeRating}）`"
                             :value="r.id" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110">
              <template #default="{ row }">
                <el-button link type="primary" size="small" :loading="binding"
                           @click="bindAndRecalc(row)">补绑并重算</el-button>
              </template>
            </el-table-column>
          </el-table>
        </template>

        <!-- 当前节点：采用 / 排除轮次 -->
        <div class="hc-section-title">本次计算采用与排除的轮次</div>
        <el-table :data="detail.currentRounds" border size="small"
                  empty-text="当前还没有任何已签核轮次">
          <el-table-column prop="playDate" label="完赛日" width="100" />
          <el-table-column prop="tournamentName" label="赛事" min-width="160" show-overflow-tooltip />
          <el-table-column prop="roundNo" label="轮" width="50" />
          <el-table-column prop="totalStrokes" label="总杆" width="60" />
          <el-table-column prop="teeName" label="梯台" min-width="140" show-overflow-tooltip />
          <el-table-column label="差值" width="80">
            <template #default="{ row }">{{ row.diffValue != null ? Number(row.diffValue).toFixed(1) : '—' }}</template>
          </el-table-column>
          <el-table-column label="采用" width="70">
            <template #default="{ row }">
              <el-tag :type="row.included ? 'success' : 'info'" size="small">
                {{ row.included ? `第${row.rankNo}低` : '排除' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="exclusionReason" label="排除原因" min-width="200" show-overflow-tooltip />
        </el-table>

        <div class="hc-actions">
          <el-button type="primary" plain size="small" @click="recalcPlayer">对该球员定向重算</el-button>
          <el-button size="small" @click="toggleOldChain">
            {{ showOldChain ? '隐藏历史口径' : '查看历史口径与重算旧链' }}
          </el-button>
        </div>

        <!-- 历史链 -->
        <template v-if="showOldChain">
          <div class="hc-section-title">历史指数（含被重算作废的旧链，均按当时规则计算）</div>
          <el-timeline>
            <el-timeline-item v-for="h in [...oldChain].reverse()" :key="h.logId"
                              :type="h.chain === 'LIVE' ? 'success' : 'info'" placement="top">
              <div class="hc-hist">
                <el-tag size="small" :type="h.chain === 'LIVE' ? 'success' : 'info'">
                  {{ h.chain === 'LIVE' ? '当前链' : '已作废旧链' }}
                </el-tag>
                <b>{{ h.asOfDate }}</b>
                <span>{{ triggerText(h.triggerType) }}</span>
                <el-tag size="small" :type="indexTag(h.status)">{{ indexStatusText(h.status) }}</el-tag>
                <span v-if="h.finalIndex != null">指数 <b>{{ Number(h.finalIndex).toFixed(1) }}</b>
                  （选轮均值 {{ h.rawIndex != null ? Number(h.rawIndex).toFixed(1) : '—' }}）</span>
                <span class="hc-rule-name">{{ h.ruleName }}（生效 {{ h.ruleEffectiveDate }}）</span>
                <div v-if="h.cappedNote" class="hc-sub">{{ h.cappedNote }}</div>
              </div>
            </el-timeline-item>
          </el-timeline>
        </template>
      </div>
    </el-drawer>
  </div>
</template>
