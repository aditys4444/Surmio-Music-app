import * as cheerio from 'cheerio';

/**
 * Normalizes a URL to prevent duplicate variations
 */
export function normalizeUrl(url) {
  try {
    const parsed = new URL(url);
    // Strip common tracking and dynamic query params that don't change content
    const cleanParams = new URLSearchParams();
    for (const [key, val] of parsed.searchParams.entries()) {
      if (!key.startsWith('utm_') && !['ref', 'fbclid', 'gclid', '_ga'].includes(key)) {
        cleanParams.append(key, val);
      }
    }
    parsed.search = cleanParams.toString();
    parsed.hash = '';
    return parsed.toString().replace(/\/+$/, '');
  } catch {
    return url.trim().replace(/\/+$/, '');
  }
}

/**
 * Resolves relative URLs to absolute based on origin
 */
function resolveUrl(relativeOrAbsolute, base) {
  try {
    return new URL(relativeOrAbsolute, base).href;
  } catch {
    return relativeOrAbsolute;
  }
}

/**
 * Formats duration seconds into mm:ss or hh:mm:ss
 */
/**
 * Scrapes YouTube search results directly to fetch live YMusic/YouTube tracks
 */
export async function searchYouTube(query, limit = 15) {
  try {
    const cleanQ = encodeURIComponent(query.trim());
    const res = await fetch(`https://www.youtube.com/results?search_query=${cleanQ}`, {
      headers: {
        'User-Agent':
          'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
        'Accept-Language': 'en-US,en;q=0.9',
      },
    });

    if (!res.ok) throw new Error(`YouTube search returned HTTP ${res.status}`);
    const html = await res.text();

    const match = html.match(/ytInitialData\s*=\s*({.+?});<\/script>/);
    if (!match) return [];

    const data = JSON.parse(match[1]);
    const contents =
      data.contents?.twoColumnSearchResultsRenderer?.primaryContents?.sectionListRenderer?.contents;

    const items = [];
    if (contents) {
      for (const section of contents) {
        const renderers = section.itemSectionRenderer?.contents || [];
        for (const r of renderers) {
          const v = r.videoRenderer;
          if (v && v.videoId && v.title?.runs?.[0]?.text) {
            const rawTitle = v.title.runs.map((x) => x.text).join('');
            const author = v.ownerText?.runs?.[0]?.text || 'YouTube Music';
            const duration = v.lengthText?.simpleText || 'Track';
            const rawThumb =
              v.thumbnail?.thumbnails?.[v.thumbnail.thumbnails.length - 1]?.url ||
              `https://i.ytimg.com/vi/${v.videoId}/maxresdefault.jpg`;
            // Strip YouTube downsampling ?sqp= parameter for crisp HD visuals
            const thumb = rawThumb.split('?')[0] || `https://i.ytimg.com/vi/${v.videoId}/maxresdefault.jpg`;

            items.push({
              title: rawTitle,
              streamUrl: `https://www.youtube.com/embed/${v.videoId}?autoplay=1&enablejsapi=1`,
              sourceUrl: `https://www.youtube.com/watch?v=${v.videoId}`,
              thumbnail: thumb,
              description: `Track by ${author} • ${duration}`,
              duration,
              category: 'Trending Music',
              author,
              isYouTube: true,
              youtubeId: v.videoId,
            });

            if (items.length >= limit) break;
          }
        }
        if (items.length >= limit) break;
      }
    }

    return items;
  } catch (err) {
    console.error('Error during YouTube search scraping:', err.message);
    return [];
  }
}


/**
 * Multi-strategy Scraper: Extracts direct video streams, titles, thumbnails, and metadata
 */
export async function scrapeUrl(targetUrl, defaultCategory = 'Web Stream') {
  const normTarget = normalizeUrl(targetUrl);
  let html = '';
  let finalUrl = normTarget;

  // Helper: Extract YouTube video ID
  const ytMatch = normTarget.match(/(?:youtube\.com\/(?:[^\/]+\/.+\/|(?:v|e(?:mbed)?)\/|.*[?&]v=)|youtu\.be\/|music\.youtube\.com\/watch\?v=)([^"&?\/\s]{11})/i);
  if (ytMatch) {
    const videoId = ytMatch[1];
    try {
      const oembedRes = await fetch(`https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=${videoId}&format=json`, {
        headers: { 'User-Agent': 'Mozilla/5.0' },
      });
      if (oembedRes.ok) {
        const meta = await oembedRes.json();
        return {
          success: true,
          sourceUrl: `https://www.youtube.com/watch?v=${videoId}`,
          items: [
            {
              title: meta.title || 'YouTube Track',
              streamUrl: `https://www.youtube.com/embed/${videoId}?autoplay=1&enablejsapi=1`,
              sourceUrl: `https://www.youtube.com/watch?v=${videoId}`,
              thumbnail: `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
              description: `Music stream by ${meta.author_name} (${meta.provider_name})`,
              duration: 'Music Track',
              category: defaultCategory === 'Custom Scraped' ? 'Music' : defaultCategory,
              author: meta.author_name || 'YouTube Music',
              isYouTube: true,
              youtubeId: videoId,
            },
          ],
        };
      }
    } catch {
      // Fallback if oembed times out
    }

    return {
      success: true,
      sourceUrl: `https://www.youtube.com/watch?v=${videoId}`,
      items: [
        {
          title: `YouTube Video (${videoId})`,
          streamUrl: `https://www.youtube.com/embed/${videoId}?autoplay=1&enablejsapi=1`,
          sourceUrl: `https://www.youtube.com/watch?v=${videoId}`,
          thumbnail: `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
          description: `Extracted YouTube music stream (${videoId})`,
          duration: 'Music Track',
          category: defaultCategory === 'Custom Scraped' ? 'Music' : defaultCategory,
          author: 'YouTube',
          isYouTube: true,
          youtubeId: videoId,
        },
      ],
    };
  }

  const isDirectMediaUrl = normTarget.match(/\.(mp4|webm|ogv|mov|m4v)(\?.*)?$/i);

  if (isDirectMediaUrl) {
    clearTimeout(timeoutId);
    const filename = normTarget.split('/').pop().split('?')[0];
    const cleanTitle = decodeURIComponent(filename)
      .replace(/\.[^/.]+$/, '')
      .replace(/[-_]/g, ' ');

    let domain = 'Direct Stream';
    try {
      domain = new URL(normTarget).hostname;
    } catch {}

    return {
      success: true,
      sourceUrl: normTarget,
      items: [
        {
          title: cleanTitle ? (cleanTitle.charAt(0).toUpperCase() + cleanTitle.slice(1)) : 'Direct Video Stream',
          streamUrl: normTarget,
          thumbnail: 'https://images.unsplash.com/photo-1534447677768-be436bb09401?w=800&q=80',
          description: `Direct video stream extracted from ${domain}`,
          duration: 'Stream',
          category: defaultCategory,
          author: domain,
        },
      ],
    };
  }

  try {
    const response = await fetch(normTarget, {
      signal: controller.signal,
      headers: {
        'User-Agent':
          'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
        Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
        'Accept-Language': 'en-US,en;q=0.9',
      },
      redirect: 'follow',
    });

    clearTimeout(timeoutId);

    if (!response.ok) {
      throw new Error(`HTTP Error ${response.status}: ${response.statusText}`);
    }

    finalUrl = response.url || normTarget;
    const contentType = response.headers.get('content-type') || '';

    // Direct video stream detected via Content-Type header
    if (contentType.includes('video/')) {
      const filename = finalUrl.split('/').pop().split('?')[0];
      const cleanTitle = decodeURIComponent(filename)
        .replace(/\.[^/.]+$/, '')
        .replace(/[-_]/g, ' ');

      return {
        success: true,
        sourceUrl: finalUrl,
        items: [
          {
            title: cleanTitle || 'Direct Video Stream',
            streamUrl: finalUrl,
            thumbnail: 'https://images.unsplash.com/photo-1518173946687-a4c8892bbd9f?w=640&q=80',
            description: `Direct video stream from ${new URL(finalUrl).hostname}`,
            duration: 'Stream',
            category: defaultCategory,
            author: new URL(finalUrl).hostname,
          },
        ],
      };
    }

    html = await response.text();
  } catch (err) {
    clearTimeout(timeoutId);
    if (err.name === 'AbortError') {
      throw new Error('Connection timed out while fetching webpage (limit 9s)');
    }
    throw new Error(err.message || 'Failed to fetch webpage content');
  }

  const $ = cheerio.load(html);
  const items = [];
  const seenStreams = new Set();

  const domain = new URL(finalUrl).hostname;
  const pageTitle =
    $('meta[property="og:title"]').attr('content') ||
    $('meta[name="twitter:title"]').attr('content') ||
    $('title').text().trim() ||
    'Untitled Video';

  const pageImage =
    $('meta[property="og:image"]').attr('content') ||
    $('meta[name="twitter:image"]').attr('content') ||
    null;

  const pageDesc =
    $('meta[property="og:description"]').attr('content') ||
    $('meta[name="description"]').attr('content') ||
    `Scraped from ${domain}`;

  // Strategy 1: HTML5 <video> elements and child <source> tags
  $('video').each((_, el) => {
    const $v = $(el);
    let src = $v.attr('src');
    const poster = $v.attr('poster') || pageImage;

    const sources = [];
    if (src) sources.push(src);

    $v.find('source').each((_, sEl) => {
      const sSrc = $(sEl).attr('src');
      if (sSrc) sources.push(sSrc);
    });

    for (const rawSrc of sources) {
      const absUrl = resolveUrl(rawSrc, finalUrl);
      if (absUrl && !seenStreams.has(absUrl)) {
        seenStreams.add(absUrl);
        items.push({
          title: pageTitle,
          streamUrl: absUrl,
          thumbnail: poster ? resolveUrl(poster, finalUrl) : null,
          description: pageDesc,
          duration: null,
          category: defaultCategory,
          author: domain,
        });
      }
    }
  });

  // Strategy 2: OpenGraph & Twitter Card Video tags
  const ogVideos = [
    $('meta[property="og:video"]').attr('content'),
    $('meta[property="og:video:url"]').attr('content'),
    $('meta[property="og:video:secure_url"]').attr('content'),
    $('meta[name="twitter:player:stream"]').attr('content'),
  ].filter(Boolean);

  for (const vSrc of ogVideos) {
    const absUrl = resolveUrl(vSrc, finalUrl);
    if (absUrl && !seenStreams.has(absUrl)) {
      seenStreams.add(absUrl);
      items.push({
        title: pageTitle,
        streamUrl: absUrl,
        thumbnail: pageImage ? resolveUrl(pageImage, finalUrl) : null,
        description: pageDesc,
        duration: null,
        category: defaultCategory,
        author: domain,
      });
    }
  }

  // Strategy 3: JSON-LD VideoObject schemas
  $('script[type="application/ld+json"]').each((_, sEl) => {
    try {
      const text = $(sEl).text().trim();
      if (!text) return;
      const data = JSON.parse(text);
      const objects = Array.isArray(data) ? data : [data];

      for (const obj of objects) {
        if (
          obj['@type'] === 'VideoObject' ||
          (Array.isArray(obj['@type']) && obj['@type'].includes('VideoObject'))
        ) {
          const stream = obj.contentUrl || obj.embedUrl;
          if (stream) {
            const absUrl = resolveUrl(stream, finalUrl);
            if (!seenStreams.has(absUrl)) {
              seenStreams.add(absUrl);
              const thumb = Array.isArray(obj.thumbnailUrl)
                ? obj.thumbnailUrl[0]
                : obj.thumbnailUrl;
              items.push({
                title: obj.name || pageTitle,
                streamUrl: absUrl,
                thumbnail: thumb ? resolveUrl(thumb, finalUrl) : pageImage,
                description: obj.description || pageDesc,
                duration: obj.duration || null,
                category: defaultCategory,
                author: obj.author?.name || domain,
              });
            }
          }
        }
      }
    } catch {
      // Ignore JSON parse errors in individual blocks
    }
  });

  // Strategy 4: Regex scan for direct video links (.mp4, .webm) inside anchor tags or scripts
  $('a[href]').each((_, aEl) => {
    const href = $(aEl).attr('href');
    if (href && href.match(/\.(mp4|webm|ogv)(\?.*)?$/i)) {
      const absUrl = resolveUrl(href, finalUrl);
      if (absUrl && !seenStreams.has(absUrl)) {
        seenStreams.add(absUrl);
        const linkText = $(aEl).text().trim();
        items.push({
          title: linkText || pageTitle,
          streamUrl: absUrl,
          thumbnail: pageImage,
          description: `Direct media stream linked from ${domain}`,
          duration: null,
          category: defaultCategory,
          author: domain,
        });
      }
    }
  });

  return {
    success: true,
    sourceUrl: finalUrl,
    items,
  };
}
