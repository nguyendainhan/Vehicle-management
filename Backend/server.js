// import express from 'express';
// import mysql from 'mysql2/promise';
// import cors from 'cors';
// import bodyParser from 'body-parser';

// const app = express();
// app.use(cors());
// app.use(bodyParser.json());

// // ==== Kết nối MySQL ====
// const db = await mysql.createPool({
//   host: 'localhost',
//   port: 3307,
//   user: 'root',
//   password: 'Nhan952002',
//   database: 'parking_system'
// });

// // ==== API ====

// // Lấy danh sách xe
// app.get('/api/vehicles', async (req, res) => {
//   const [rows] = await db.query('SELECT * FROM vehicles ORDER BY created_at DESC');
//   res.json(rows);
// });

// // Thêm xe mới
// app.post('/api/vehicles', async (req, res) => {
//   const { plate_number, owner_name } = req.body;
//   try {
//     const [result] = await db.query(
//       'INSERT INTO vehicles (plate_number, owner_name) VALUES (?, ?)',
//       [plate_number.toUpperCase(), owner_name || null]
//     );
//     res.json({ id: result.insertId, plate_number: plate_number.toUpperCase(), owner_name });
//   } catch (err) {
//     res.status(400).json({ error: 'Biển số đã tồn tại hoặc dữ liệu không hợp lệ' });
//   }
// });

// // Kiểm tra xe có được phép và lưu log với action
// app.post('/api/vehicles/check', async (req, res) => {
//   const { plate, action } = req.body;
//   const [rows] = await db.query(
//     'SELECT * FROM vehicles WHERE plate_number = ? AND is_active = TRUE',
//     [plate]
//   )
//   const allowed = rows.length > 0

//   await db.query(
//     'INSERT INTO access_logs (plate_number, status, action) VALUES (?, ?, ?)',
//     [plate, allowed ? 'ALLOW' : 'DENY', action === 'IN' ? 'IN' : 'OUT']
//   )

//   res.json({ allowed })
// })

// // Xóa xe
// app.delete('/api/vehicles/:id', async (req, res) => {
//   await db.query('DELETE FROM vehicles WHERE id = ?', [req.params.id]);
//   res.json({ success: true });
// });

// // Lấy log ra/vào
// app.get('/api/logs', async (req, res) => {
//   const [rows] = await db.query('SELECT * FROM access_logs ORDER BY created_at DESC LIMIT 100');
//   res.json(rows);
// });

// // Start server
// const PORT = 3000;
// app.listen(PORT, () => console.log(`Server running at http://localhost:${PORT}`));
// Backend/server.js
// Backend/server.js
import express from 'express';
import mysql from 'mysql2/promise';
import cors from 'cors';
import bodyParser from 'body-parser';
import path from 'path';
import { fileURLToPath } from 'url';
import dotenv from 'dotenv';
dotenv.config();

// ==== Setup __dirname cho ES Module ====
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
app.use(cors());
app.use(bodyParser.json());

// ==== Kết nối MySQL ====
const db = await mysql.createPool({
  host: process.env.DB_HOST,
  port: process.env.DB_PORT,
  user: process.env.DB_USER,
  password: process.env.DB_PASSWORD,
  database: process.env.DB_NAME
});

// ==== API ====

// Lấy danh sách xe
app.get('/api/vehicles', async (req, res) => {
  const [rows] = await db.query('SELECT * FROM vehicles ORDER BY created_at DESC');
  res.json(rows);
});

// Thêm xe mới
app.post('/api/vehicles', async (req, res) => {
  const { plate_number, owner_name } = req.body;
  try {
    const [result] = await db.query(
      'INSERT INTO vehicles (plate_number, owner_name) VALUES (?, ?)',
      [plate_number.toUpperCase(), owner_name || null]
    );
    res.json({ id: result.insertId, plate_number: plate_number.toUpperCase(), owner_name });
  } catch (err) {
    res.status(400).json({ error: 'Biển số đã tồn tại hoặc dữ liệu không hợp lệ' });
  }
});

// Kiểm tra xe vào/ra và lưu log
app.post('/api/vehicles/check', async (req, res) => {
  const { plate, action } = req.body;
  const [rows] = await db.query(
    'SELECT * FROM vehicles WHERE plate_number = ? AND is_active = TRUE',
    [plate]
  );
  const allowed = rows.length > 0;

  await db.query(
    'INSERT INTO access_logs (plate_number, status, action) VALUES (?, ?, ?)',
    [plate, allowed ? 'ALLOW' : 'DENY', action === 'IN' ? 'IN' : 'OUT']
  );

  res.json({ allowed });
});

// Xóa xe
app.delete('/api/vehicles/:id', async (req, res) => {
  await db.query('DELETE FROM vehicles WHERE id = ?', [req.params.id]);
  res.json({ success: true });
});

// Lấy log ra/vào
app.get('/api/logs', async (req, res) => {
  const [rows] = await db.query('SELECT * FROM access_logs ORDER BY created_at DESC LIMIT 100');
  res.json(rows);
});

// ==== Serve Vue SPA ====
// Phục vụ thư mục dist của Vue
app.use(express.static(path.join(__dirname, '../Frontend/dist')));

// Nếu không trùng API nào, trả về index.html
app.get(/.*/, (req, res) => {
  res.sendFile(path.join(__dirname, '../Frontend/dist/index.html'));
});

// ==== Start server ====
const PORT = 3000;
app.listen(PORT, () => console.log(`Server running at http://localhost:${PORT}`));
