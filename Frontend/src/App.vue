<template>
  <div class="app">
    <h2>🚗 停車管制小工具 (Nâng cao)</h2>

    <div class="container">
      <!-- Cột trái: Nhập biển số -->
      <div class="col input-col">
        <h3>➕ Thêm biển số</h3>
        <input v-model="manualInput" placeholder="ABC-123" />
        <button @click="addPlate">Thêm</button>

        <ul>
          <li v-for="v in vehicles" :key="v.id">
            {{ v.plate_number }}
            <button @click="deletePlate(v.id)">❌ Xóa</button>
          </li>
        </ul>
      </div>

      <!-- Cột giữa: Camera + OCR -->
      <div class="col camera-col">
        <h3>📷 Quét biển số</h3>
        <select v-model="actionType">
          <option value="IN">Xe vào</option>
          <option value="OUT">Xe ra</option>
        </select>
        <video ref="video" autoplay playsinline></video>
        <button @click="capture">Chụp & Quét</button>
        <canvas ref="canvas" hidden></canvas>

        <p v-if="loading">⏳ Đang nhận diện...</p>
        <p v-if="scannedPlate">🔍 Biển số: <strong>{{ scannedPlate }}</strong></p>
        <p v-if="result === true" class="ok">✅ Cho phép</p>
        <p v-if="result === false" class="no">❌ Không cho phép</p>
      </div>

      <!-- Cột phải: Log ra/vào -->
      <div class="col log-col">
        <h3>📝 Lịch sử ra/vào (100 bản gần nhất)</h3>
        <table>
          <thead>
            <tr>
              <th>Biển số</th>
              <th>Trạng thái</th>
              <th>Thời gian</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="log in logs" :key="log.id">
              <td>{{ log.plate_number }}</td>
              <td :class="log.status === 'ALLOW' ? 'ok' : 'no'">{{ log.action }} - {{ log.status }}</td>
              <td>{{ new Date(log.created_at).toLocaleString() }}</td>
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

// ===== DATA =====
const vehicles = ref([])
const logs = ref([])
const manualInput = ref('')
const scannedPlate = ref('')
const result = ref(null)
const loading = ref(false)
const video = ref(null)
const canvas = ref(null)
const actionType = ref('IN')
const API_URL = 'http://localhost:3000' // Thay đổi nếu cần

// ===== FETCH VEHICLES & LOGS =====
const fetchVehicles = async () => {
  try {
    const res = await axios.get(`${API_URL}/api/vehicles`)
    vehicles.value = res.data
  } catch (err) { console.error(err) }
}

const fetchLogs = async () => {
  try {
    const res = await axios.get(`${API_URL}/api/logs`)
    logs.value = res.data
  } catch (err) { console.error(err) }
}

// ===== MOUNTED =====
onMounted(async () => {
  await nextTick()
  await fetchVehicles()
  await fetchLogs()

  try {
    const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })
    if (video.value) video.value.srcObject = stream
  } catch (err) {
    console.error('Camera error:', err)
    alert('Không truy cập được camera. Cho phép quyền camera!')
  }
})

// ===== ADD PLATE =====
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
    alert('Biển số đã tồn tại hoặc lỗi dữ liệu')
  }
}

// ===== DELETE PLATE =====
const deletePlate = async (id) => {
  if (!confirm('Bạn có chắc muốn xóa xe này?')) return
  try {
    await axios.delete(`${API_URL}/api/vehicles/${id}`)
    vehicles.value = vehicles.value.filter(v => v.id !== id)
  } catch (err) { console.error(err) }
}

// ===== CAPTURE IMAGE =====
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

// ===== OCR =====
const scanPlate = async () => {
  if (!canvas.value) return
  const blob = await new Promise(resolve => canvas.value.toBlob(resolve, 'image/png'))
  if (!blob) { loading.value = false; return }

  try {
    const { data } = await Tesseract.recognize(blob, 'eng', { logger: m => console.log(m) })
    const text = data.text.toUpperCase()
    const match = text.match(/[A-Z0-9/-]{5,10}/)
    scannedPlate.value = match ? match[0] : 'KHÔNG NHẬN DIỆN'

    await checkPlate()
    await fetchLogs() // Cập nhật log realtime
  } catch (err) {
    console.error('Tesseract error:', err)
    alert('Nhận diện thất bại')
  } finally { loading.value = false }
}

// ===== CHECK PLATE =====
const checkPlate = async () => {
  if (scannedPlate.value === 'KHÔNG NHẬN DIỆN') return
  try {
    const res = await axios.post(`${API_URL}/api/vehicles/check`, {
      plate: scannedPlate.value,
      action: actionType.value // IN hoặc OUT
    })
    result.value = res.data.allowed
    await fetchLogs()
  } catch (err) {
    console.error('Check plate error:', err)
    alert('Lỗi khi kiểm tra biển số')
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
