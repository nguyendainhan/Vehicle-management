<template>
  <div class="app">
    <h2>🚗 停車管制小工具（進階版）</h2>

    <div class="container">
      <!-- 左欄：新增車牌 -->
      <div class="col input-col">
        <h3>➕ 新增車牌</h3>
        <input v-model="manualInput" placeholder="ABC-123" />
        <button @click="addPlate">新增</button>

        <ul>
          <li v-for="v in vehicles" :key="v.id">
            {{ v.plate_number }}
            <button @click="deletePlate(v.id)">❌ 刪除</button>
          </li>
        </ul>
      </div>

      <!-- 中間欄：相機 + OCR -->
      <div class="col camera-col">
        <h3>📷 掃描車牌</h3>
        <select v-model="actionType">
          <option value="IN">車輛進場</option>
          <option value="OUT">車輛出場</option>
        </select>

        <video ref="video" autoplay playsinline></video>
        <button @click="capture">拍照並辨識</button>
        <canvas ref="canvas" hidden></canvas>

        <p v-if="loading">⏳ 辨識中...</p>
        <p v-if="scannedPlate">🔍 車牌號碼：<strong>{{ scannedPlate }}</strong></p>
        <p v-if="result === true" class="ok">✅ 允許通行</p>
        <p v-if="result === false" class="no">❌ 禁止通行</p>
      </div>

      <!-- 右欄：進出紀錄 -->
      <div class="col log-col">
        <h3>📝 進出紀錄（最近 100 筆）</h3>
        <table>
          <thead>
            <tr>
              <th>車牌</th>
              <th>狀態</th>
              <th>時間</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="log in logs" :key="log.id">
              <td>{{ log.plate_number }}</td>
              <td :class="log.status === 'ALLOW' ? 'ok' : 'no'">
                {{ log.action }} - {{ log.status }}
              </td>
              <td>{{ formatTaiwanTime(log.created_at) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import Tesseract from 'tesseract.js'
import axios from 'axios'

// ===== 資料 =====
const vehicles = ref([])
const logs = ref([])
const manualInput = ref('')
const scannedPlate = ref('')
const result = ref(null)
const loading = ref(false)
const video = ref(null)
const canvas = ref(null)
const actionType = ref('IN')
const API_URL = 'http://localhost:3000' // 依實際情況修改

const formatTaiwanTime = (time) => {
  if (!time) return ''
  return new Date(time).toLocaleString('zh-TW', {
    timeZone: 'Asia/Taipei',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  })
}


// ===== 取得車牌清單 =====
const fetchVehicles = async () => {
  try {
    const res = await axios.get(`${API_URL}/api/vehicles`)
    vehicles.value = res.data
  } catch (err) {
    console.error(err)
  }
}

// ===== 取得進出紀錄 =====
const fetchLogs = async () => {
  try {
    const res = await axios.get(`${API_URL}/api/logs`)
    logs.value = res.data
  } catch (err) {
    console.error(err)
  }
}

// ===== 頁面載入 =====
onMounted(async () => {
  await nextTick()
  await fetchVehicles()
  await fetchLogs()

  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: 'environment' }
    })
    if (video.value) video.value.srcObject = stream
  } catch (err) {
    console.error('相機錯誤：', err)
    alert('無法存取相機，請允許相機權限')
  }
})

// ===== 新增車牌 =====
const addPlate = async () => {
  if (!manualInput.value.trim()) return
  try {
    const res = await axios.post(`${API_URL}/api/vehicles`, {
      plate_number: manualInput.value.trim().toUpperCase(),
      owner_name: ''
    })
    vehicles.value.push(res.data)
    manualInput.value = ''
  } catch (err) {
    alert('車牌已存在或資料錯誤')
  }
}

// ===== 刪除車牌 =====
const deletePlate = async (id) => {
  if (!confirm('確定要刪除此車牌嗎？')) return
  try {
    await axios.delete(`${API_URL}/api/vehicles/${id}`)
    vehicles.value = vehicles.value.filter(v => v.id !== id)
  } catch (err) {
    console.error(err)
  }
}

// ===== 拍照 =====
const capture = async () => {
  if (!video.value || !canvas.value) return
  loading.value = true
  result.value = null

  const ctx = canvas.value.getContext('2d')
  canvas.value.width = video.value.videoWidth
  canvas.value.height = video.value.videoHeight
  ctx.drawImage(video.value, 0, 0)

  await scanPlate()
}

// ===== OCR 辨識 =====
const scanPlate = async () => {
  if (!canvas.value) return
  const blob = await new Promise(resolve =>
    canvas.value.toBlob(resolve, 'image/png')
  )

  if (!blob) {
    loading.value = false
    return
  }

  try {
    const { data } = await Tesseract.recognize(blob, 'eng')
    const text = data.text.toUpperCase()
    const match = text.match(/[A-Z0-9/-]{5,10}/)
    scannedPlate.value = match ? match[0] : '無法辨識'

    await checkPlate()
    await fetchLogs()
  } catch (err) {
    console.error('OCR 錯誤：', err)
    alert('辨識失敗')
  } finally {
    loading.value = false
  }
}

// ===== 檢查車牌 =====
const checkPlate = async () => {
  if (scannedPlate.value === '無法辨識') return
  try {
    const res = await axios.post(`${API_URL}/api/vehicles/check`, {
      plate: scannedPlate.value,
      action: actionType.value // IN / OUT
    })
    result.value = res.data.allowed
    await fetchLogs()
  } catch (err) {
    console.error('檢查車牌錯誤：', err)
    alert('檢查車牌時發生錯誤')
  }
}
</script>

<style scoped>
.app {
  max-width: 100%;
  margin: auto;
  font-family: sans-serif;
  padding: 10px;
}

h2 {
  text-align: center;
  margin-bottom: 20px;
}

.container {
  display: flex;
  gap: 20px;
  flex-wrap: wrap;
}

.col {
  flex: 1;
  min-width: 280px;
  background: #f9f9f9;
  padding: 10px;
  border-radius: 8px;
}

/* Camera video */
video {
  width: 100%;
  border-radius: 8px;
  margin-bottom: 8px;
}

/* Input */
input {
  width: 95%;
  padding: 8px;
  margin-bottom: 6px;
}

button {
  padding: 6px 10px;
  margin-left: 6px;
}

.ok {
  color: green;
  font-weight: bold;
}

.no {
  color: red;
  font-weight: bold;
}

table {
  width: 100%;
  border-collapse: collapse;
}

table th,
table td {
  border: 1px solid #ccc;
  padding: 4px 6px;
  text-align: left;
}

/* Mobile */
@media (max-width: 768px) {
  .container {
    flex-direction: column;
  }

  .col {
    width: 100%;
  }
}
</style>
