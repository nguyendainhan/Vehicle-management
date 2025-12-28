<script setup>
const message = 'toggle-sidebar';
const number = 42;
</script>
<template>
  <h1>Sidebar Component</h1>
  {{ message }}
  {{ number + 10 }}
</template>

<style scoped>
button {
  padding: 10px 20px;
  background-color: #42b983;
  color: white;
  border: none;
}
</style>
