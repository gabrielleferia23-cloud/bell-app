// Copies the file-reading libraries into www/vendor so Bell works offline.
const fs = require("fs");
const path = require("path");
const out = path.join(__dirname, "..", "www", "vendor");
fs.mkdirSync(out, { recursive: true });
const files = {
  "pdf.min.js": "pdfjs-dist/build/pdf.min.js",
  "pdf.worker.min.js": "pdfjs-dist/build/pdf.worker.min.js",
  "xlsx.full.min.js": "xlsx/dist/xlsx.full.min.js",
  "mammoth.browser.min.js": "mammoth/mammoth.browser.min.js",
  "jszip.min.js": "jszip/dist/jszip.min.js",
};
for (const [name, from] of Object.entries(files)) {
  fs.copyFileSync(require.resolve(from), path.join(out, name));
  console.log("vendored", name);
}
