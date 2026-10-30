import express from 'express';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { Ytdl } from '@distube/ytdl-core';
import { searchYouTube } from './scraper.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
const PORT = process.env.PORT || 3000;
const DATA_DIR = path.join(__dirname, 'data');
const DB_FILE = path.join(DATA_DIR, 'videos.json');

app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

// Ensure data directory exists
if (!fs.existsSync(DATA_DIR)) {
  fs.mkdirSync(DATA_DIR, { recursive: true });
}

function loadDatabase() {
  try {
    if (fs.existsSync(DB_FILE)) {
      const raw = fs.readFileSync(DB_FILE, 'utf-8');
      const parsed = JSON.parse(raw);
      if (parsed && Array.isArray(parsed.videos)) {
        return parsed;
      }
    }
  } catch (err) {
    console.error('Error reading database file:', err.message);
  }
  return { videos: [], downloadedTracks: [], searchHistory: [], duplicateCountTotal: 0 };
}

function saveDatabase(db) {
  try {
    fs.writeFileSync(DB_FILE, JSON.stringify(db, null, 2), 'utf-8');
  } catch (err) {
    console.error('Error saving database file:', err.message);
  }
}

// GET /api/videos - returns tracks matching filters, search, and category
app.get('/api/videos', (req, res) => {
  const db = loadDatabase();
  const { search, category, sort, filter } = req.query;
  let items = [...(db.videos || [])];

  if (category && category !== 'All') {
    items = items.filter((v) => v.category?.toLowerCase() === category.toLowerCase());
  }

  if (filter === 'favorite') {
    items = items.filter((v) => v.isFavorite === true);
  } else if (filter === 'audio') {
    items = items.filter((v) => v.mediaType === 'audio' || v.hasVideo === false);
  } else if (filter === 'video') {
    items = items.filter((v) => v.hasVideo === true);
  }

  if (search) {
    const q = search.toLowerCase().trim();
    items = items.filter(
      (v) =>
        (v.title && v.title.toLowerCase().includes(q)) ||
        (v.author && v.author.toLowerCase().includes(q)) ||
        (v.category && v.category.toLowerCase().includes(q)) ||
        (v.description && v.description.toLowerCase().includes(q))
    );
  }

  if (sort === 'oldest') {
    items.sort((a, b) => new Date(a.addedAt || 0) - new Date(b.addedAt || 0));
  } else if (sort === 'title') {
    items.sort((a, b) => (a.title || '').localeCompare(b.title || ''));
  } else if (sort === 'popular') {
    items.sort((a, b) => (b.views || '').localeCompare(a.views || ''));
  }

  const categories = Array.from(new Set((db.videos || []).map((v) => v.category).filter(Boolean)));

  res.json({
    total: (db.videos || []).length,
    matching: items.length,
    categories: ['All', ...categories],
    videos: items,
  });
});

// GET /api/feed - Infinite feed for Spotify-style continuous discovery
app.get('/api/feed', (req, res) => {
  const db = loadDatabase();
  const page = parseInt(req.query.page) || 1;
  const limit = parseInt(req.query.limit) || 12;
  const category = req.query.category || 'All';

  let pool = [...(db.videos || [])];
  if (category && category !== 'All') {
    pool = pool.filter((v) => v.category?.toLowerCase() === category.toLowerCase());
  }

  if (pool.length === 0) {
    return res.json({ success: true, page, limit, hasMore: false, items: [] });
  }

  // Loop back seamlessly if user scrolls past end of catalog
  const offset = ((page - 1) * limit) % pool.length;
  const pageItems = [];
  for (let i = 0; i < limit; i++) {
    const item = pool[(offset + i) % pool.length];
    if (item) {
      pageItems.push({
        ...item,
        id: `${item.id}-page-${page}-${i}`,
      });
    }
  }

  res.json({
    success: true,
    page,
    limit,
    category,
    hasMore: true,
    items: pageItems,
  });
});

// GET /api/search - Live instant search across title, artist, lyrics, category + live YMusic
app.get('/api/search', async (req, res) => {
  const db = loadDatabase();
  const rawQ = (req.query.q || '').trim();
  const q = rawQ.toLowerCase();
  if (!q) {
    return res.json({ success: true, results: [] });
  }

  const localResults = (db.videos || []).filter((v) => {
    const inTitle = v.title && v.title.toLowerCase().includes(q);
    const inAuthor = v.author && v.author.toLowerCase().includes(q);
    const inCat = v.category && v.category.toLowerCase().includes(q);
    const inDesc = v.description && v.description.toLowerCase().includes(q);
    const inLyrics = v.lyrics && v.lyrics.some((l) => l.text.toLowerCase().includes(q));
    return inTitle || inAuthor || inCat || inDesc || inLyrics;
  });

  // Also query live YMusic / YouTube for fresh tracks
  let liveYt = [];
  try {
    liveYt = await searchYouTube(rawQ, 12);
  } catch (_) {}

  // Format live tracks into player-compatible objects & auto-persist to database
  let dbChanged = false;
  if (!db.videos) db.videos = [];
  const existingYtIds = new Set(db.videos.map((v) => v.youtubeId || v.id).filter(Boolean));

  const formattedLive = liveYt.map((yt) => {
    const item = {
      id: 'ymusic-' + yt.youtubeId,
      title: yt.title,
      author: yt.author || 'YouTube Music',
      duration: yt.duration || '3:30',
      thumbnail: yt.thumbnail,
      streamUrl: `https://www.youtube.com/embed/${yt.youtubeId}?autoplay=1&enablejsapi=1`,
      sourceUrl: yt.sourceUrl || `https://www.youtube.com/watch?v=${yt.youtubeId}`,
      category: yt.category || 'Trending Music',
      isYouTube: true,
      youtubeId: yt.youtubeId,
      hasVideo: true,
      views: 'YouTube Stream',
      description: `Direct YMusic stream from ${yt.author}`,
      fallbackUrl: '/media/oceans.mp4',
      lyrics: [
        { time: 0, text: `♪ ${yt.title} ♪` },
        { time: 10, text: `Vocals: ${yt.author || 'Artist'}` },
        { time: 25, text: `Streaming live on ALYVEX Unified Engine` }
      ],
      addedAt: new Date().toISOString(),
    };

    if (yt.youtubeId && !existingYtIds.has(yt.youtubeId)) {
      existingYtIds.add(yt.youtubeId);
      db.videos.unshift(item);
      dbChanged = true;
    }
    return item;
  });

  if (dbChanged) {
    saveDatabase(db);
  }

  // Combine and deduplicate
  const seenIds = new Set(localResults.map((r) => r.youtubeId || r.id));
  const combined = [...localResults];
  formattedLive.forEach((item) => {
    if (!seenIds.has(item.youtubeId)) {
      seenIds.add(item.youtubeId);
      combined.push(item);
    }
  });

  res.json({ success: true, query: rawQ, results: combined });
});

// GET /api/ymusic/search - Direct live YMusic search & auto-save to database
app.get('/api/ymusic/search', async (req, res) => {
  const q = (req.query.q || '').trim();
  if (!q) return res.json({ success: true, items: [] });
  try {
    const items = await searchYouTube(q, 15);
    const db = loadDatabase();
    if (!db.videos) db.videos = [];
    const existing = new Set(db.videos.map((v) => v.youtubeId || v.id).filter(Boolean));
    let added = false;

    items.forEach((yt) => {
      if (yt.youtubeId && !existing.has(yt.youtubeId)) {
        existing.add(yt.youtubeId);
        db.videos.unshift({
          id: 'ymusic-' + yt.youtubeId,
          title: yt.title,
          author: yt.author || 'YouTube Music',
          duration: yt.duration || '3:30',
          thumbnail: yt.thumbnail,
          streamUrl: `https://www.youtube.com/embed/${yt.youtubeId}?autoplay=1&enablejsapi=1`,
          sourceUrl: yt.sourceUrl || `https://www.youtube.com/watch?v=${yt.youtubeId}`,
          category: yt.category || 'YouTube Music',
          isYouTube: true,
          youtubeId: yt.youtubeId,
          hasVideo: true,
          views: 'Real YMusic Stream',
          description: `Live YouTube Music stream by ${yt.author}`,
          fallbackUrl: '/media/oceans.mp4',
          addedAt: new Date().toISOString()
        });
        added = true;
      }
    });

    if (added) {
      saveDatabase(db);
    }

    res.json({ success: true, items });
  } catch (err) {
    res.json({ success: false, items: [], error: err.message });
  }
});

// GET /api/category/sync - Live scrape songs for a specific category
app.get('/api/category/sync', async (req, res) => {
  const category = (req.query.cat || '').trim();
  if (!category) return res.json({ success: false, error: 'Category required' });

  const query = `${category} Top Songs 2024`;
  try {
    const items = await searchYouTube(query, 10);
    const db = loadDatabase();
    if (!db.videos) db.videos = [];
    const existing = new Set(db.videos.map((v) => v.youtubeId || v.id).filter(Boolean));
    let added = 0;

    items.forEach((yt) => {
      if (yt.youtubeId && !existing.has(yt.youtubeId)) {
        existing.add(yt.youtubeId);
        db.videos.push({
          id: 'ymusic-' + yt.youtubeId,
          title: yt.title,
          author: yt.author || 'YouTube Music',
          duration: yt.duration || '3:30',
          thumbnail: yt.thumbnail,
          streamUrl: `https://www.youtube.com/embed/${yt.youtubeId}?autoplay=1&enablejsapi=1`,
          sourceUrl: yt.sourceUrl || `https://www.youtube.com/watch?v=${yt.youtubeId}`,
          category: category,
          isYouTube: true,
          youtubeId: yt.youtubeId,
          hasVideo: true,
          views: 'Live Stream',
          description: `Scraped stream for ${category}`,
          fallbackUrl: '/media/oceans.mp4',
          addedAt: new Date().toISOString()
        });
        added++;
      }
    });

    if (added > 0) {
      saveDatabase(db);
    }

    const categoryTracks = db.videos.filter((v) => v.category?.toLowerCase() === category.toLowerCase());
    res.json({ success: true, category, added, total: categoryTracks.length, tracks: categoryTracks });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

// GET /api/suggestions - Real-time autocomplete suggestions & matched tracks
app.get('/api/suggestions', (req, res) => {
  const db = loadDatabase();
  const q = (req.query.q || '').trim();
  if (!q) {
    return res.json({ success: true, query: '', suggestions: [], tracks: [], topMatch: null });
  }

  const queryLower = q.toLowerCase();
  const matchedTracks = (db.videos || []).filter(
    (v) =>
      v.title.toLowerCase().includes(queryLower) ||
      (v.author && v.author.toLowerCase().includes(queryLower)) ||
      (v.category && v.category.toLowerCase().includes(queryLower))
  );

  const suggestions = [];
  matchedTracks.forEach((t) => {
    if (suggestions.length < 5) suggestions.push(t.title);
    if (suggestions.length < 5 && t.author) suggestions.push(t.author);
  });

  const topMatch = matchedTracks[0] || null;

  res.json({
    success: true,
    query: q,
    suggestions: Array.from(new Set(suggestions)),
    tracks: matchedTracks.slice(0, 6),
    topMatch,
  });
});

// POST /api/videos/:id/favorite - Toggle favorite status
app.post('/api/videos/:id/favorite', (req, res) => {
  const db = loadDatabase();
  const { id } = req.params;
  const track = (db.videos || []).find((v) => v.id === id);
  if (!track) {
    return res.status(404).json({ success: false, error: 'Track not found' });
  }
  track.isFavorite = !track.isFavorite;
  saveDatabase(db);
  res.json({ success: true, isFavorite: track.isFavorite, id });
});

// GET /api/downloads - List downloaded tracks
app.get('/api/downloads', (req, res) => {
  const db = loadDatabase();
  res.json({ success: true, downloads: db.downloadedTracks || [] });
});

const AUDIO_CACHE_DIR = path.join(__dirname, '.cache', 'audio');
if (!fs.existsSync(AUDIO_CACHE_DIR)) {
  fs.mkdirSync(AUDIO_CACHE_DIR, { recursive: true });
}
const activeAudioDownloads = new Map();

function streamAudioFile(filePath, req, res, options = {}) {
  const stat = fs.statSync(filePath);
  const fileSize = stat.size;
  const range = req.headers.range;
  const ext = path.extname(filePath).toLowerCase();
  const contentType = (ext === '.mp4' || ext === '.m4a') ? 'audio/mp4' : ext === '.wav' ? 'audio/wav' : ext === '.ogg' ? 'audio/ogg' : 'audio/mpeg';
  const cleanTitle = (options.title || 'track').replace(/[/\\?%*:|"<>]/g, '_').trim() || 'track';
  const safeAsciiTitle = cleanTitle.replace(/[^\x20-\x7E]/g, '_').replace(/["\\]/g, '') || 'track';
  const encodedUtf8Title = encodeURIComponent(cleanTitle);
  const isDownload = options.isDownload || false;
  const disposition = isDownload
    ? `attachment; filename="${safeAsciiTitle}.mp3"; filename*=UTF-8''${encodedUtf8Title}.mp3`
    : 'inline';

  res.setHeader('Accept-Ranges', 'bytes');
  res.setHeader('Access-Control-Allow-Origin', '*');

  if (range) {
    const parts = range.replace(/bytes=/, '').split('-');
    const start = parseInt(parts[0], 10);
    const end = parts[1] ? parseInt(parts[1], 10) : fileSize - 1;
    if (start >= fileSize) {
      res.status(416).setHeader('Content-Range', `bytes */${fileSize}`);
      return res.end();
    }
    const chunksize = end - start + 1;
    const file = fs.createReadStream(filePath, { start, end });
    res.writeHead(206, {
      'Content-Range': `bytes ${start}-${end}/${fileSize}`,
      'Accept-Ranges': 'bytes',
      'Content-Length': chunksize,
      'Content-Type': contentType,
      'Content-Disposition': disposition,
    });
    file.pipe(res);
  } else {
    res.writeHead(200, {
      'Content-Length': fileSize,
      'Content-Type': contentType,
      'Accept-Ranges': 'bytes',
      'Content-Disposition': disposition,
    });
    fs.createReadStream(filePath).pipe(res);
  }
}

// POST /api/preload/:id - Proactively warm up audio cache for next track
app.post('/api/preload/:id', (req, res) => {
  const { id } = req.params;
  const db = loadDatabase();
  const track = (db.videos || []).find((v) => v.id === id || v.youtubeId === id);
  let videoId = track?.youtubeId || (id.startsWith('ymusic-') ? id.replace('ymusic-', '') : (id.length === 11 ? id : null));
  if (!videoId && track?.sourceUrl) {
    const m = track.sourceUrl.match(/(?:v=|\/embed\/|youtu\.be\/)([a-zA-Z0-9_-]{11})/);
    if (m) videoId = m[1];
  }

  if (!videoId) return res.json({ success: false, message: 'Not a YouTube track' });

  const cachedAudioPath = path.join(AUDIO_CACHE_DIR, `${videoId}.m4a`);
  if (fs.existsSync(cachedAudioPath) && fs.statSync(cachedAudioPath).size > 50000) {
    return res.json({ success: true, cached: true });
  }

  if (activeAudioDownloads.has(videoId)) {
    return res.json({ success: true, downloading: true });
  }

  // Start background warm-up
  activeAudioDownloads.set(videoId, true);
  const partAudioPath = path.join(AUDIO_CACHE_DIR, `${videoId}.m4a.part`);
  const ytUrl = `https://www.youtube.com/watch?v=${videoId}`;
  try {
    const ytdl = new Ytdl();
    const stream = ytdl.download(ytUrl, { filter: (f) => f.hasAudio && !f.hasVideo, quality: 'highestaudio' });
    const writeStream = fs.createWriteStream(partAudioPath);

    stream.pipe(writeStream);
    writeStream.on('finish', () => {
      activeAudioDownloads.delete(videoId);
      try {
        if (fs.existsSync(partAudioPath) && fs.statSync(partAudioPath).size > 50000) {
          fs.renameSync(partAudioPath, cachedAudioPath);
        }
      } catch (_) {}
    });
    writeStream.on('error', () => {
      activeAudioDownloads.delete(videoId);
      try { if (fs.existsSync(partAudioPath)) fs.unlinkSync(partAudioPath); } catch (_) {}
    });
    stream.on('error', () => {
      activeAudioDownloads.delete(videoId);
      try { if (fs.existsSync(partAudioPath)) fs.unlinkSync(partAudioPath); } catch (_) {}
    });
  } catch (err) {
    activeAudioDownloads.delete(videoId);
  }

  return res.json({ success: true, started: true });
});

// GET /api/stream/:id - Stream Router for verified audio assets with HTTP 206 Range support
app.get('/api/stream/:id', async (req, res) => {
  const { id } = req.params;
  const db = loadDatabase();
  const track = (db.videos || []).find((v) => v.id === id || v.youtubeId === id);

  const isDownload = req.query.download === '1' || req.query.download === 'true';
  const cleanTitle = (track && track.title ? track.title : 'track').replace(/[/\\?%*:|"<>]/g, '_').trim() || 'track';

  // 1. Check for genuine local file path
  let genuineFilePath = null;
  if (track && track.localPath && fs.existsSync(track.localPath)) {
    genuineFilePath = track.localPath;
  } else if (track && track.mediaFile && fs.existsSync(path.join(__dirname, 'public', 'media', track.mediaFile))) {
    genuineFilePath = path.join(__dirname, 'public', 'media', track.mediaFile);
  } else if (id && fs.existsSync(path.join(__dirname, 'public', 'media', id))) {
    genuineFilePath = path.join(__dirname, 'public', 'media', id);
  }

  if (genuineFilePath) {
    return streamAudioFile(genuineFilePath, req, res, { title: cleanTitle, isDownload });
  }

  // 2. Handle YouTube streams with real live audio extraction and local caching
  let videoId = null;
  if (track && track.youtubeId) {
    videoId = track.youtubeId;
  } else if (id && id.startsWith('ymusic-')) {
    videoId = id.replace('ymusic-', '');
  } else if (track && track.id && track.id.startsWith('ymusic-')) {
    videoId = track.id.replace('ymusic-', '');
  } else if (track && track.sourceUrl) {
    const m = track.sourceUrl.match(/(?:v=|\/embed\/|youtu\.be\/)([a-zA-Z0-9_-]{11})/);
    if (m) videoId = m[1];
  } else if (id && id.length === 11) {
    videoId = id;
  }

  if (videoId) {
    const cachedAudioPath = path.join(AUDIO_CACHE_DIR, `${videoId}.m4a`);
    const partAudioPath = path.join(AUDIO_CACHE_DIR, `${videoId}.m4a.part`);

    // If already cached on disk, serve immediately with full Range (seeking) support
    if (fs.existsSync(cachedAudioPath)) {
      try {
        const sz = fs.statSync(cachedAudioPath).size;
        if (sz > 50000) {
          return streamAudioFile(cachedAudioPath, req, res, { title: cleanTitle, isDownload });
        }
      } catch (_) {}
    }

    try {
      const ytdl = new Ytdl();
      const ytUrl = `https://www.youtube.com/watch?v=${videoId}`;
      const safeAsciiTitle = cleanTitle.replace(/[^\x20-\x7E]/g, '_').replace(/["\\]/g, '') || 'track';
      const encodedUtf8Title = encodeURIComponent(cleanTitle);
      const disposition = isDownload
        ? `attachment; filename="${safeAsciiTitle}.mp3"; filename*=UTF-8''${encodedUtf8Title}.mp3`
        : 'inline';

      res.setHeader('Content-Type', 'audio/mp4');
      res.setHeader('Content-Disposition', disposition);
      res.setHeader('Accept-Ranges', 'bytes');
      res.setHeader('Access-Control-Allow-Origin', '*');

      const audioStream = ytdl.download(ytUrl, {
        filter: (f) => f.hasAudio && !f.hasVideo,
        quality: 'highestaudio',
      });

      // Tee to disk cache if not already caching
      if (!isDownload && !fs.existsSync(cachedAudioPath) && !activeAudioDownloads.has(videoId)) {
        activeAudioDownloads.set(videoId, true);
        const writeStream = fs.createWriteStream(partAudioPath);
        audioStream.pipe(writeStream);

        writeStream.on('finish', () => {
          activeAudioDownloads.delete(videoId);
          try {
            if (fs.existsSync(partAudioPath) && fs.statSync(partAudioPath).size > 50000) {
              fs.renameSync(partAudioPath, cachedAudioPath);
            }
          } catch (_) {}
        });

        writeStream.on('error', () => {
          activeAudioDownloads.delete(videoId);
          try { if (fs.existsSync(partAudioPath)) fs.unlinkSync(partAudioPath); } catch (_) {}
        });
      }

      audioStream.on('error', (err) => {
        console.error('YTDL stream error:', err.message);
        activeAudioDownloads.delete(videoId);
        try { if (fs.existsSync(partAudioPath)) fs.unlinkSync(partAudioPath); } catch (_) {}
        if (!res.headersSent) {
          res.status(500).json({
            success: false,
            code: 'YTDL_STREAM_ERROR',
            error: 'Failed to stream audio: ' + err.message,
            trackId: id,
          });
        }
      });

      audioStream.pipe(res);
      return;
    } catch (ytdlErr) {
      console.error('YTDL init error:', ytdlErr.message);
      activeAudioDownloads.delete(videoId);
      return res.status(500).json({
        success: false,
        code: 'YTDL_INIT_ERROR',
        error: 'Failed to initialize audio stream: ' + ytdlErr.message,
        trackId: id,
      });
    }
  }

  return res.status(404).json({
    success: false,
    error: 'Audio source not found for this track',
    trackId: id,
  });
});

// POST /api/download - Add a verified downloaded track to downloads
app.post('/api/download', (req, res) => {
  const db = loadDatabase();
  const { trackId, format, trackData, verifiedRealAudio } = req.body;
  let track = (db.videos || []).find((v) => v.id === trackId || v.youtubeId === trackId);
  if (!track && trackData) {
    track = trackData;
  }
  if (!track) {
    return res.status(404).json({ success: false, error: 'Track not found' });
  }

  // Do not fake downloads for YouTube streams without verified audio
  if (!verifiedRealAudio && (track.isYouTube || track.youtubeId || (trackId && trackId.startsWith('ymusic-')))) {
    return res.status(501).json({
      success: false,
      code: 'YOUTUBE_STREAM_DOWNLOAD_UNAVAILABLE',
      error: 'Cannot register an offline download without genuine verified audio data.',
    });
  }

  if (!db.downloadedTracks) db.downloadedTracks = [];

  const existing = db.downloadedTracks.find((d) => (d.trackId === trackId || d.id === trackId) && d.format === format);
  if (existing) {
    return res.json({ success: true, message: 'Already in downloads', download: existing });
  }

  const isVideo = (format || '').toLowerCase().includes('mp4') || (format || '').toLowerCase().includes('video');
  const size = req.body.size || (isVideo ? '14.5 MB' : '4.2 MB');

  const downloadItem = {
    id: `dl-${Date.now()}`,
    trackId: track.id,
    title: track.title,
    author: track.author,
    thumbnail: track.thumbnail,
    duration: track.duration,
    format: format || 'Audio MP3 (320kbps)',
    size,
    status: 'completed',
    verifiedRealAudio: true,
    date: 'Just now',
    streamUrl: track.streamUrl,
  };

  db.downloadedTracks.unshift(downloadItem);
  saveDatabase(db);

  res.json({
    success: true,
    message: `Saved "${track.title}" to Downloads (${downloadItem.format})`,
    download: downloadItem,
  });
});

// DELETE /api/downloads/:id - Remove a download
app.delete('/api/downloads/:id', (req, res) => {
  const db = loadDatabase();
  const { id } = req.params;
  if (!db.downloadedTracks) db.downloadedTracks = [];
  const initial = db.downloadedTracks.length;
  db.downloadedTracks = db.downloadedTracks.filter((d) => d.id !== id);
  if (db.downloadedTracks.length === initial) {
    return res.status(404).json({ success: false, error: 'Download not found' });
  }
  saveDatabase(db);
  res.json({ success: true, message: 'Download removed' });
});

// GET /api/search-history - Get search history
app.get('/api/search-history', (req, res) => {
  const db = loadDatabase();
  res.json({ success: true, history: db.searchHistory || [] });
});

// POST /api/search-history - Add search query
app.post('/api/search-history', (req, res) => {
  const db = loadDatabase();
  const { query } = req.body;
  if (!query || !query.trim()) return res.json({ success: false });
  const q = query.trim();
  if (!db.searchHistory) db.searchHistory = [];
  db.searchHistory = db.searchHistory.filter((item) => (item.query || item).toLowerCase() !== q.toLowerCase());
  db.searchHistory.unshift({ id: `sh-${Date.now()}`, query: q, time: 'Just now' });
  if (db.searchHistory.length > 20) db.searchHistory.pop();
  saveDatabase(db);
  res.json({ success: true, history: db.searchHistory });
});

// DELETE /api/search-history - Clear search history
app.delete('/api/search-history', (req, res) => {
  const db = loadDatabase();
  db.searchHistory = [];
  saveDatabase(db);
  res.json({ success: true, history: [] });
});

// GET /api/reload-db - Force reload from disk
app.get('/api/reload-db', (req, res) => {
  const db = loadDatabase();
  res.json({ success: true, count: (db.videos || []).length });
});

// ==========================================
// Comprehensive Auto-Fetch Engine (Live YouTube Music Sync)
// ==========================================
const AUTO_FETCH_QUERIES = [
  { category: 'Bollywood', query: 'Best Bollywood Songs 2024' },
  { category: 'Bollywood', query: 'Arijit Singh Latest Hits Songs' },
  { category: 'Bollywood', query: 'Top Romantic Hindi Songs 2024' },
  { category: 'Devotional / Bhakti', query: 'Shiv Tandav Stotram Shankar Mahadevan' },
  { category: 'Devotional / Bhakti', query: 'Hanuman Chalisa Gulshan Kumar' },
  { category: 'Devotional / Bhakti', query: 'Shri Ram Bhajan Superhit' },
  { category: 'Devotional / Bhakti', query: 'Krishna Bhajans Superhit' },
  { category: 'Punjabi', query: 'Top Punjabi Songs 2024' },
  { category: 'Punjabi', query: 'Diljit Dosanjh Best Songs' },
  { category: 'Punjabi', query: 'Karan Aujla Latest Songs' },
  { category: 'Punjabi', query: 'Sidhu Moosewala Top Songs' },
  { category: 'Yo Yo Honey Singh', query: 'Yo Yo Honey Singh Glory' },
  { category: 'Yo Yo Honey Singh', query: 'Honey Singh All Time Hits Jukebox' },
  { category: 'Yo Yo Honey Singh', query: 'Yo Yo Honey Singh Party Songs' },
  { category: 'Lofi & Chill', query: 'Bollywood Lofi Chill Mashup' },
  { category: 'Lofi & Chill', query: 'Hindi Lofi Songs Midnight Relax' },
  { category: 'Lofi & Chill', query: 'Indian Lofi Beats Study Work' },
  { category: 'Electronic / EDM', query: 'Alan Walker Top Hits' },
  { category: 'Electronic / EDM', query: 'Marshmello Best Songs' },
  { category: 'Electronic / EDM', query: 'Top EDM Electronic Dance Music 2024' },
  { category: 'Pop & Synthwave', query: 'Top English Pop Hits 2024' },
  { category: 'Pop & Synthwave', query: 'The Weeknd Best Hits' },
  { category: 'Pop & Synthwave', query: 'Global Billboard Hits 2024' }
];

let isAutoFetching = false;
let lastAutoFetchTime = null;

async function autoFetchAllContent() {
  if (isAutoFetching) {
    console.log('[Auto-Fetch] Sync already in progress, skipping duplicate call.');
    const db = loadDatabase();
    return { success: true, inProgress: true, total: (db.videos || []).length, videos: db.videos };
  }

  isAutoFetching = true;
  console.log('[Auto-Fetch] 🚀 Starting auto-fetch across all genres from live YouTube Music...');

  const db = loadDatabase();
  if (!db.videos) db.videos = [];
  const existingIds = new Set(db.videos.map((v) => v.youtubeId).filter(Boolean));
  let addedCount = 0;

  for (const item of AUTO_FETCH_QUERIES) {
    try {
      const results = await searchYouTube(item.query, 6);
      for (const yt of results) {
        if (yt.youtubeId && !existingIds.has(yt.youtubeId)) {
          existingIds.add(yt.youtubeId);
          const track = {
            id: `ymusic-${yt.youtubeId}`,
            title: yt.title,
            author: yt.author || 'YouTube Music',
            duration: yt.duration || '3:45',
            thumbnail: yt.thumbnail,
            streamUrl: `https://www.youtube.com/embed/${yt.youtubeId}?autoplay=1&enablejsapi=1`,
            sourceUrl: yt.sourceUrl || `https://www.youtube.com/watch?v=${yt.youtubeId}`,
            category: item.category,
            isYouTube: true,
            youtubeId: yt.youtubeId,
            hasVideo: true,
            views: '10M+ views',
            description: `Live streaming track by ${yt.author}`,
            fallbackUrl: '/media/oceans.mp4',
            lyrics: [
              { time: 0, text: `♪ ${yt.title} ♪` },
              { time: 10, text: `Vocals: ${yt.author || 'Artist'}` },
              { time: 25, text: `Streaming live on ALYVEX Unified Engine` },
              { time: 45, text: `Enjoy studio acoustic performance...` }
            ],
            addedAt: new Date().toISOString()
          };
          db.videos.push(track);
          addedCount++;
        }
      }
      // Polite rate-limiting delay between queries to ensure YouTube scraper never gets blocked
      await new Promise((r) => setTimeout(r, 1200));
    } catch (err) {
      console.warn(`[Auto-Fetch] Warning for "${item.query}":`, err.message);
    }
  }

  if (addedCount > 0) {
    saveDatabase(db);
    console.log(`[Auto-Fetch] ✓ Successfully added ${addedCount} new songs! Total catalog: ${db.videos.length}`);
  } else {
    console.log(`[Auto-Fetch] ✓ Catalog is fully up to date. Total songs: ${db.videos.length}`);
  }

  isAutoFetching = false;
  lastAutoFetchTime = new Date().toISOString();

  return {
    success: true,
    addedCount,
    total: db.videos.length,
    lastAutoFetchTime,
    videos: db.videos
  };
}

// GET /api/autofetch - Trigger or get auto-fetched contents
app.get('/api/autofetch', async (req, res) => {
  try {
    const result = await autoFetchAllContent();
    res.json(result);
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

// POST /api/autofetch - Also support POST
app.post('/api/autofetch', async (req, res) => {
  try {
    const result = await autoFetchAllContent();
    res.json(result);
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

// GET /api/autofetch/status - Check sync status
app.get('/api/autofetch/status', (req, res) => {
  const db = loadDatabase();
  res.json({
    success: true,
    isAutoFetching,
    lastAutoFetchTime,
    total: (db.videos || []).length
  });
});

app.listen(PORT, () => {
  console.log(`ALYVEX Unified Server listening on http://localhost:${PORT}`);
  // Run auto-fetch in background on startup after 1 second
  setTimeout(() => {
    autoFetchAllContent().catch((e) => console.error('[Auto-Fetch] Initial run error:', e.message));
  }, 1000);

  // Periodically refresh and scrape new content every 10 minutes
  setInterval(() => {
    autoFetchAllContent().catch((e) => console.error('[Auto-Fetch] Periodic run error:', e.message));
  }, 10 * 60 * 1000);
});

