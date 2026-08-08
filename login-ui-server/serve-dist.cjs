const http = require('http');
const fs = require('fs');
const path = require('path');
const dist = path.join(__dirname, 'dist');
const mime = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript',
  '.css': 'text/css',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.json': 'application/json',
};
const types = { 'application/javascript': 'js', 'text/css': 'css' };
http.createServer((req, res) => {
  let url = req.url.split('?')[0];
  let p = decodeURIComponent(url);
  let f = path.join(dist, p);
  // SPA fallback: root or extension-less → index.html
  if (p === '/' || !path.extname(f) || p === '/index.html') f = path.join(dist, 'index.html');
  fs.readFile(f, (err, data) => {
    if (err) {
      res.statusCode = 404;
      res.end('404: ' + p);
      return;
    }
    res.setHeader('Content-Type', mime[path.extname(f)] || 'application/octet-stream');
    res.end(data);
  });
}).listen(9091, '0.0.0.0', () => console.log('dist static server on http://0.0.0.0:9091'));
