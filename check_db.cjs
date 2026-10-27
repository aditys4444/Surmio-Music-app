const fs = require('fs');
const db = JSON.parse(fs.readFileSync('data/videos.json', 'utf8'));
console.log('Root keys in videos.json:', Object.keys(db));
if (db.downloadedTracks) {
  console.log('downloadedTracks length:', db.downloadedTracks.length);
}
