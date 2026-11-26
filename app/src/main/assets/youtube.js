(() => {
  if (window.__speedShield) return;
  window.__speedShield = true;

  // One rule per selector, so one unsupported selector cannot disable the rest.
  const hidden = [
    'ad-slot-renderer',
    'ytm-rich-item-renderer:has(ad-slot-renderer)',
    'ytm-promoted-sparkles-web-renderer',
    'ytm-promoted-sparkles-text-search-renderer',
    'ytm-promoted-video-renderer',
    'ytm-companion-ad-renderer',
    'ytm-companion-slot',
    'ytm-statement-banner-renderer',
    '.ytp-ad-overlay-container'
  ];
  const style = document.createElement('style');
  style.textContent = hidden.map(s => s + '{display:none!important}').join('\n');

  const saved = new WeakMap();

  function tick() {
    if (!style.isConnected) document.documentElement.appendChild(style);

    // If a video ad plays, mute it, jump to its end and press Skip.
    for (const player of document.querySelectorAll('.html5-video-player')) {
      const video = player.querySelector('video');
      if (!video) continue;

      if (player.classList.contains('ad-showing')) {
        if (!saved.has(video)) saved.set(video, { muted: video.muted, rate: video.playbackRate });
        video.muted = true;
        video.playbackRate = 16;
        if (Number.isFinite(video.duration) && video.currentTime < video.duration - 0.5) {
          video.currentTime = video.duration - 0.1;
        }
        player.querySelector(
          '.ytp-skip-ad-button, .ytp-ad-skip-button, .ytp-ad-skip-button-modern'
        )?.click();
      } else if (saved.has(video)) {
        const before = saved.get(video);
        video.muted = before.muted;
        video.playbackRate = before.rate;
        saved.delete(video);
      }
    }
  }

  tick();
  setInterval(tick, 150);
})();
