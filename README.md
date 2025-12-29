# Parking System (Vue + Express + MySQL)

## 1. Clone project
git clone https://github.com/nguyendainhan/Vehicle-management.git

## 2. Cài backend
cd Backend
npm install

## 3. Import database
mysql -u root -p -P 3307 < database/parking_system.sql

## 4. Tạo file .env
copy .env.example .env

## 5. Run backend
node server.js

## 6. Run frontend
cd Frontend
npm install
npm run dev
