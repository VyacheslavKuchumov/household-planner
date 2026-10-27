import '@fontsource/roboto/400.css'
import '@fontsource/roboto/500.css'
import '@fontsource/roboto/700.css'
import '@mdi/font/css/materialdesignicons.css'
import 'vuetify/styles'
import './style.css'

import { createApp } from 'vue'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import App from './App.vue'
import { router } from './router'

const vuetify = createVuetify({
  components,
  directives,
  icons: {
    defaultSet: 'mdi',
  },
  theme: {
    defaultTheme: 'plannerLight',
    themes: {
      plannerLight: {
        dark: false,
        colors: {
          background: '#f5f6f8',
          surface: '#ffffff',
          primary: '#486b59',
          secondary: '#dfe9df',
          accent: '#e4a66f',
          error: '#c9685e',
          info: '#6988a3',
          success: '#568269',
          warning: '#d79c51',
        },
      },
    },
  },
})

createApp(App).use(vuetify).use(router).mount('#app')
