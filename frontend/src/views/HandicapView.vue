<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

// ============================================================
// 差点指数管理（管理员）
// 页签：① 梯台难度参数  ② 计算规则（版本化/生效日）  ③ 重算批次（按赛事日期分批 + 失败明细）
// ============================================================

const activeTab = ref('rules')
const loading = ref(false)

// ---------------- 规则 ----------------
const rules = ref([])
const ruleDialog = ref(false)
const ruleSaving = ref(false)
const ruleForm = ref(blankRule())

function blankRule() {
  return {
    code: '', name: '', effectiveDate: '', windowRounds: 20, minRounds: 3,
    countTableJson: JSON.stringify({
      3: 1, 4: 1, 5: 2, 6: 2, 7: 3, 8: 4, 9: 4, 10: 5,
      11: 5, 12: 6, 13: 6, 14: 7, 15: 7, 16: 8, 17: 8, 18: 9, 19: 9, 20: 10
    }),
    maxDiffBelowIndex: 10.0, maxDecreasePerRound: 5.0, holeScoreCap: null,
    indexMin: -10.0, indexMax: 54.0, note: ''
  }
}

async function loadRules() {
  rules.value = await api.listRules()
}
function openRuleCreate() { ruleForm.value = blankRule(); ruleDialog.value = true }
async function saveRule() {
  ruleSaving.value = true
  try {
    const d = { ...ruleForm.value }
    d.windowRounds = Number(d.windowRounds)
    d.minRounds = Number(d.minRounds)
    d.maxDiffBelowIndex = Number(d.maxDiffBelowIndex)
    d.maxDecreasePerRound = Number(d.maxDecreasePerRound)
    d.holeScoreCap = d.holeScoreCap === '' || d.holeScoreCap == null ? null : Number(d.holeScoreCap)
    d.indexMin = Number(d.indexMin)
    d.indexMax = Number(d.indexMax)
    try { d.countTableJson = JSON.stringify(JSON.parse(d.countTableJson)) } catch { /* 后端校验 */ }
    await api.createRule(d)
    ElMessage.success('规则已建立，自生效日起的计算才会采用')
    ruleDialog.value = false
    await loadRules()
  } catch (e) { ElMessage.error(e.message) } finally { ruleSaving.value = false }
}
async function toggleRule(row) {
  try {
    await api.toggleRule(row.id, !row.enabled)
    ElMessage.success(!row.enabled ? '规则已启用' : '规则已停用（历史日志保留引用）')
    await loadRules()
  } catch (e) { ElMessage.error(e.message) }
}

// ---------------- 梯台 ----------------
const ratings = ref([])
const ratingDialog = ref(false)
const ratingSaving = ref(false)
const ratingForm = ref(blankRating())

function blankRating() {
  return { courseName: '', teeName: '', courseRatingValue: 72.0, slopeRating: 113, parTotal: 72, note: '' }
}
async function loadRatings() {
  ratings.value = await api.listRatings()
}
function openRatingCreate() { ratingForm.value = blankRating(); ratingDialog.value = true }
async function saveRating() {
  ratingSaving.value = true
  try {
    const d = {
      ...ratingForm.value,
      courseRatingValue: Number(ratingForm.value.courseRatingValue),
      slopeRating: Number(ratingForm.value.slopeRating),
      parTotal: ratingForm.value.parTotal === '' || ratingForm.value.parTotal == null
        ? null : Number(ratingForm.value.parTotal)
    }
    await api.createRating(d)
    ElMessage.success('梯台难度参数已建立')
    ratingDialog.value = false
    await loadRatings()
  } catch (e) { ElMessage.error(e.message) } finally { ratingSaving.value = false }
}

// 补齐/修正梯台后定向重算所有绑定该梯台的已签核卡对应球员
async function recalcByRating(row) {
  try {
    await ElMessageBox.confirm(
      `将重算所有绑定「${row.courseName} · ${row.teeName}」的球员差点链，历史结果会保留为旧链，是否继续？`,
      '按梯台定向重算', { type: 'warning' })
    const batch = await api.targetRecalc({ ratingId: row.id })
    ElMessage.success(`批次 ${batch.batchNo} 已提交，后台执行`)
    await loadBatches()
    activeTab.value = 'batches'
    setTimeout(() => openBatch(batch.id), 600)
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.message || String(e)) }
}

// ---------------- 重算批次 ----------------
const batches = ref([])
const fullDialog = ref(false)
const fullForm = ref({ from: '', to: '' })
const batchDetail = ref(null)
const batchDetailLoading = ref(false)
const detailTimer = ref(null)

async function loadBatches() {
  batches.value = await api.listRecalcBatches()
}
function openFullDialog() { fullForm.value = { from: '', to: '' }; fullDialog.value = true }
async function submitFull() {
  try {
    const p = {}
    if (fullForm.value.from) p.from = fullForm.value.from
    if (fullForm.value.to) p.to = fullForm.value.to
    const batch = await api.fullRecalc(p)
    ElMessage.success(`全量重算批次 ${batch.batchNo} 已创建，按赛事日期分批后台执行`)
    fullDialog.value = false
    await loadBatches()
    activeTab.value = 'batches'
    setTimeout(() => openBatch(batch.id), 600)
  } catch (e) { ElMessage.error(e.message) }
}
async function openBatch(id) {
  batchDetailLoading.value = true
  try {
    batchDetail.value = await api.getRecalcBatch(id)
    if (['排队中', '执行中'].includes(batchDetail.value.status)) {
      clearTimeout(detailTimer.value)
      detailTimer.value = setTimeout(() => openBatch(id), 1500)
    }
  } catch (e) { ElMessage.error(e.message) } finally { batchDetailLoading.value = false }
}

const batchTag = (s) => ({
  排队中: 'info', 执行中: 'warning', 已完成: 'success', 部分失败: 'danger', 失败: 'danger'
}[s] || 'info')
const statusTag = (s) => ({ CALCULATED: 'success', INSUFFICIENT: 'warning', NO_RULE: 'info' }[s] || 'info')

async function loadAll() {
  loading.value = true
  try {
    await Promise.all([loadRules(), loadRatings(), loadBatches()])
  } catch (e) { ElMessage.error(e.message) } finally { loading.value = false }
}
onMounted(loadAll)
</script>

<template>
  <div v-loading="loading">
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">差点指数 · 规则与重算</span>
        <span class="panel-desc">规则按生效日版本化，新规则只影响生效日之后；重算保留旧链、逐轮记录采用/排除原因</span>
      </div>

      <el-tabs v-model="activeTab">
        <!-- 规则 -->
        <el-tab-pane label="计算规则" name="rules">
          <div class="toolbar">
            <span class="spacer"></span>
            <el-button type="primary" @click="openRuleCreate">新建规则（带生效日）</el-button>
          </div>
          <el-table :data="rules" border stripe>
            <el-table-column prop="code" label="编码" width="110" />
            <el-table-column prop="name" label="规则名称" min-width="200" />
            <el-table-column prop="effectiveDate" label="生效日" width="110" />
            <el-table-column label="窗口/最少样本" width="110">
              <template #default="{ row }">{{ row.windowRounds }} / {{ row.minRounds }}</template>
            </el-table-column>
            <el-table-column prop="maxDiffBelowIndex" label="异常阈值" width="90" />
            <el-table-column prop="maxDecreasePerRound" label="下调上限" width="90" />
            <el-table-column prop="holeScoreCap" label="逐洞封顶" width="90">
              <template #default="{ row }">{{ row.holeScoreCap ?? '不封顶' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用中' : '已停用' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110">
              <template #default="{ row }">
                <el-button link type="primary" @click="toggleRule(row)">{{ row.enabled ? '停用' : '启用' }}</el-button>
              </template>
            </el-table-column>
            <el-table-column prop="note" label="说明" min-width="220" show-overflow-tooltip />
          </el-table>
          <div class="hint">已生效规则锁定不可编辑；需要调整阈值时请新建一条未来生效的规则，
            历史差点仍按当时规则回放，不会被新口径改写。新规则只影响生效日之后的计算。</div>
        </el-tab-pane>

        <!-- 梯台 -->
        <el-tab-pane label="球场难度参数（梯台）" name="ratings">
          <div class="toolbar">
            <span class="spacer"></span>
            <el-button type="primary" @click="openRatingCreate">新建梯台</el-button>
          </div>
          <el-table :data="ratings" border stripe>
            <el-table-column prop="courseName" label="场地" min-width="140" />
            <el-table-column prop="teeName" label="梯台" width="100" />
            <el-table-column prop="courseRatingValue" label="球场难度值" width="110" />
            <el-table-column prop="slopeRating" label="坡度难度" width="90" />
            <el-table-column prop="parTotal" label="标准杆合计" width="100" />
            <el-table-column prop="note" label="说明" min-width="180" show-overflow-tooltip />
            <el-table-column label="操作" width="150">
              <template #default="{ row }">
                <el-button link type="warning" @click="recalcByRating(row)">补齐后定向重算</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="hint">旧数据没绑梯台的已签核轮会在球员页标「难度参数缺失」：先在这里建/补梯台，
            再给记分卡补绑（记分卡页），或直接点「补齐后定向重算」只重算受影响球员。</div>
        </el-tab-pane>

        <!-- 重算批次 -->
        <el-tab-pane label="重算批次" name="batches">
          <div class="toolbar">
            <span class="spacer"></span>
            <el-button type="primary" @click="openFullDialog">全量重算（按赛事日期分批）</el-button>
          </div>
          <el-table :data="batches" border stripe @row-click="r => openBatch(r.id)"
                    :row-style="{ cursor: 'pointer' }">
            <el-table-column prop="batchNo" label="批次号" width="180" />
            <el-table-column label="类型" width="80">
              <template #default="{ row }">{{ row.batchType === 'FULL' ? '全量' : '定向' }}</template>
            </el-table-column>
            <el-table-column label="赛事日期区间" width="200">
              <template #default="{ row }">
                {{ row.dateFrom || '最早' }} ~ {{ row.dateTo || '最新' }}
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }"><el-tag :type="batchTag(row.status)">{{ row.status }}</el-tag></template>
            </el-table-column>
            <el-table-column label="球员 成功/失败" width="120">
              <template #default="{ row }">{{ row.successPlayers }} / {{ row.failedPlayers }}（共 {{ row.totalPlayers }}）</template>
            </el-table-column>
            <el-table-column prop="totalLogs" label="计算节点" width="90" />
            <el-table-column prop="failCount" label="失败条数" width="90" />
            <el-table-column prop="failDetail" label="备注" min-width="180" show-overflow-tooltip />
          </el-table>

          <el-dialog v-model="batchDetail" :show-close="true" title="批次详情与失败明细" width="720px"
                     @closed="clearTimeout(detailTimer)">
            <div v-if="batchDetail" v-loading="batchDetailLoading">
              <el-descriptions :column="3" border size="small">
                <el-descriptions-item label="批次号">{{ batchDetail.batchNo }}</el-descriptions-item>
                <el-descriptions-item label="状态">
                  <el-tag :type="batchTag(batchDetail.status)">{{ batchDetail.status }}</el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="球员成功/失败">
                  {{ batchDetail.successPlayers }} / {{ batchDetail.failedPlayers }}
                </el-descriptions-item>
                <el-descriptions-item label="开始">{{ batchDetail.startedAt }}</el-descriptions-item>
                <el-descriptions-item label="结束">{{ batchDetail.finishedAt }}</el-descriptions-item>
                <el-descriptions-item label="计算节点">{{ batchDetail.totalLogs }}</el-descriptions-item>
              </el-descriptions>
              <el-table :data="batchDetail.failures" border size="small" class="fail-table"
                        empty-text="无失败明细">
                <el-table-column prop="playerName" label="球员" width="120" />
                <el-table-column prop="asOfDate" label="截止日" width="110" />
                <el-table-column prop="stage" label="失败阶段" width="130" />
                <el-table-column prop="errorMessage" label="错误信息" min-width="220" show-overflow-tooltip />
              </el-table>
            </div>
          </el-dialog>
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- 新建规则 -->
    <el-dialog v-model="ruleDialog" title="新建差点计算规则" width="640px">
      <el-form :model="ruleForm" label-width="130px" class="dialog-form">
        <el-form-item label="编码"><el-input v-model="ruleForm.code" placeholder="如 WHS-V3" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="ruleForm.name" /></el-form-item>
        <el-form-item label="生效日期">
          <el-date-picker v-model="ruleForm.effectiveDate" type="date" value-format="YYYY-MM-DD"
                          placeholder="选择生效日（该日起的计算才采用）" style="width:100%" />
        </el-form-item>
        <el-form-item label="窗口轮次"><el-input-number v-model="ruleForm.windowRounds" :min="1" :max="100" /></el-form-item>
        <el-form-item label="最少样本轮次"><el-input-number v-model="ruleForm.minRounds" :min="1" :max="20" /></el-form-item>
        <el-form-item label="异常差值阈值">
          <el-input-number v-model="ruleForm.maxDiffBelowIndex" :min="0.1" :step="0.5" />
          <span class="form-hint">高出窗口最佳差值多少即排除</span>
        </el-form-item>
        <el-form-item label="单次下调上限">
          <el-input-number v-model="ruleForm.maxDecreasePerRound" :min="0.1" :step="0.5" />
          <span class="form-hint">一次重算最多降多少</span>
        </el-form-item>
        <el-form-item label="逐洞封顶">
          <el-input-number v-model="ruleForm.holeScoreCap" :min="1" :max="15" placeholder="空=不封顶" />
          <span class="form-hint">留空表示不封顶</span>
        </el-form-item>
        <el-form-item label="指数下限"><el-input-number v-model="ruleForm.indexMin" :step="0.5" /></el-form-item>
        <el-form-item label="指数上限"><el-input-number v-model="ruleForm.indexMax" :step="0.5" /></el-form-item>
        <el-form-item label="选轮表 JSON" class="full">
          <el-input v-model="ruleForm.countTableJson" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="说明" class="full"><el-input v-model="ruleForm.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleDialog = false">取消</el-button>
        <el-button type="primary" :loading="ruleSaving" @click="saveRule">建立（仅未来生效可改）</el-button>
      </template>
    </el-dialog>

    <!-- 新建梯台 -->
    <el-dialog v-model="ratingDialog" title="新建球场难度参数（梯台）" width="560px">
      <el-form :model="ratingForm" label-width="110px" class="dialog-form">
        <el-form-item label="场地名称"><el-input v-model="ratingForm.courseName" placeholder="如 A场主球场" /></el-form-item>
        <el-form-item label="梯台"><el-input v-model="ratingForm.teeName" placeholder="如 白梯" /></el-form-item>
        <el-form-item label="球场难度值">
          <el-input-number v-model="ratingForm.courseRatingValue" :min="40" :max="90" :step="0.1" :precision="1" />
        </el-form-item>
        <el-form-item label="坡度难度">
          <el-input-number v-model="ratingForm.slopeRating" :min="55" :max="155" />
        </el-form-item>
        <el-form-item label="标准杆合计"><el-input-number v-model="ratingForm.parTotal" :min="1" :max="90" /></el-form-item>
        <el-form-item label="说明" class="full"><el-input v-model="ratingForm.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ratingDialog = false">取消</el-button>
        <el-button type="primary" :loading="ratingSaving" @click="saveRating">建立</el-button>
      </template>
    </el-dialog>

    <!-- 全量重算分批 -->
    <el-dialog v-model="fullDialog" title="全量重算（按赛事日期分批）" width="480px">
      <el-form label-width="110px">
        <el-form-item label="赛事日期从">
          <el-date-picker v-model="fullForm.from" type="date" value-format="YYYY-MM-DD"
                          placeholder="空=最早" style="width:100%" />
        </el-form-item>
        <el-form-item label="到">
          <el-date-picker v-model="fullForm.to" type="date" value-format="YYYY-MM-DD"
                          placeholder="空=最新" style="width:100%" />
        </el-form-item>
      </el-form>
      <div class="hint">只作废并重算区间内日期的 LIVE 节点，更早历史保持不变；逐洞成绩经派生层一次聚合，
        不会逐球员反复扫描；单球员失败会记录明细，不影响其他球员。</div>
      <template #footer>
        <el-button @click="fullDialog = false">取消</el-button>
        <el-button type="primary" @click="submitFull">创建批次</el-button>
      </template>
    </el-dialog>
  </div>
</template>
