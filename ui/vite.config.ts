import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  build: {
    // Into the SOURCE resources folder, not target/classes.
    //
    // The obvious choice is target/classes/static — no generated files in the source tree. It was
    // also wrong: target/classes is written by anything that compiles, and an IDE build does that
    // without ever running the Maven phase that produces the console. The result was a jar that
    // worked and an IDE run that returned 404 for every page, repeatedly, with nothing to explain
    // why. Output that only exists when built one particular way is output that will go missing.
    //
    // Here it is an ordinary resource: Maven copies it, the IDE copies it, `spring-boot:run` copies
    // it. The directory is git-ignored, so nothing generated is ever committed.
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    // `npm run dev` gives hot reload while still talking to the real engine on 8090. Without this
    // the dev server and the API are different origins and the session cookie is dropped.
    proxy: {
      '/api': 'http://localhost:8090',
      '/oauth2': 'http://localhost:8090',
      '/login': 'http://localhost:8090',
    },
  },
})
