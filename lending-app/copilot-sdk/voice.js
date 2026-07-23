/*
 * voice.js — browser voice engine for the LendCopilot (mobile-friendly).
 *
 * Speech-to-text and text-to-speech with graceful provider selection:
 *   • If a Sarvam API key is configured  -> Sarvam STT (saarika) + TTS (bulbul).
 *   • Otherwise (or if Sarvam fails/CORS) -> the browser's Web Speech API
 *     (webkitSpeechRecognition + speechSynthesis), which needs no key and works
 *     on mobile Chrome.
 *
 * The key is read from localStorage['sarvamKey'] or window.SARVAM_API_KEY and is
 * never bundled into committed source.
 *
 * Exposes global `LendVoice`.
 */
(function (global) {
  'use strict';

  var LANG = 'en-IN';
  var SPEAKER = 'anushka';

  var analyser = null;
  var rafId = null;
  var currentAmp = 0;
  var activeStream = null;
  var recognition = null;
  var currentAudio = null;
  var ampCtx = null;

  /* ---------- key + config ---------- */

  function getKey() {
    try {
      var k = localStorage.getItem('sarvamKey');
      if (k && k.trim()) return k.trim();
    } catch (e) {}
    return (global.SARVAM_API_KEY || '').trim();
  }
  function setKey(k) { try { localStorage.setItem('sarvamKey', (k || '').trim()); } catch (e) {} }
  function setLang(l) { LANG = l || 'en-IN'; }
  function setSpeaker(s) { SPEAKER = s || 'anushka'; }

  function hasWebSpeechSTT() { return !!(global.SpeechRecognition || global.webkitSpeechRecognition); }
  function hasWebSpeechTTS() { return !!global.speechSynthesis; }
  function isSupported() { return !!(getKey() || hasWebSpeechSTT() || hasWebSpeechTTS()); }
  function canListen() { return !!(getKey() || hasWebSpeechSTT()); }

  function bcp47(lang) { return lang || 'en-IN'; }

  /* ---------- amplitude (for the Siri orb) ---------- */

  function getAmplitude() { return currentAmp; }

  function startAmpFromStream(stream) {
    try {
      var Ctx = global.AudioContext || global.webkitAudioContext;
      ampCtx = new Ctx();
      var src = ampCtx.createMediaStreamSource(stream);
      var an = ampCtx.createAnalyser();
      an.fftSize = 1024;
      src.connect(an);
      analyser = an;
      runAmpLoop();
    } catch (e) {}
  }
  function runAmpLoop() {
    if (!analyser) return;
    var buf = new Uint8Array(analyser.fftSize);
    var loop = function () {
      if (!analyser) return;
      analyser.getByteTimeDomainData(buf);
      var sum = 0;
      for (var i = 0; i < buf.length; i++) { var x = (buf[i] - 128) / 128; sum += x * x; }
      currentAmp = Math.min(1, Math.sqrt(sum / buf.length) * 3.2);
      rafId = requestAnimationFrame(loop);
    };
    loop();
  }
  function stopAmp() {
    if (rafId) cancelAnimationFrame(rafId);
    rafId = null; analyser = null; currentAmp = 0;
    if (ampCtx) { try { ampCtx.close(); } catch (e) {} ampCtx = null; }
  }

  /* ---------- WAV encoding (for Sarvam STT) ---------- */

  function audioBufferToWav16k(buffer) {
    var targetRate = 16000;
    var data = buffer.getChannelData(0);
    if (buffer.numberOfChannels > 1) {
      var b = buffer.getChannelData(1);
      var mix = new Float32Array(data.length);
      for (var i = 0; i < data.length; i++) mix[i] = (data[i] + b[i]) / 2;
      data = mix;
    }
    var ratio = buffer.sampleRate / targetRate;
    var outLen = Math.floor(data.length / ratio);
    var view = new DataView(new ArrayBuffer(44 + outLen * 2));
    function str(off, s) { for (var j = 0; j < s.length; j++) view.setUint8(off + j, s.charCodeAt(j)); }
    str(0, 'RIFF'); view.setUint32(4, 36 + outLen * 2, true); str(8, 'WAVE');
    str(12, 'fmt '); view.setUint32(16, 16, true); view.setUint16(20, 1, true); view.setUint16(22, 1, true);
    view.setUint32(24, targetRate, true); view.setUint32(28, targetRate * 2, true);
    view.setUint16(32, 2, true); view.setUint16(34, 16, true);
    str(36, 'data'); view.setUint32(40, outLen * 2, true);
    var off = 44;
    for (var k = 0; k < outLen; k++) {
      var s = Math.max(-1, Math.min(1, data[Math.floor(k * ratio)]));
      view.setInt16(off, s < 0 ? s * 0x8000 : s * 0x7FFF, true);
      off += 2;
    }
    return new Blob([view.buffer], { type: 'audio/wav' });
  }

  /* ---------- recording with silence endpointing ---------- */

  function recordUntilSilence(maxMs, silenceMs) {
    maxMs = maxMs || 12000; silenceMs = silenceMs || 1300;
    return navigator.mediaDevices.getUserMedia({ audio: true }).then(function (stream) {
      activeStream = stream;
      startAmpFromStream(stream);
      var chunks = [];
      var rec = new MediaRecorder(stream);
      rec.ondataavailable = function (e) { if (e.data && e.data.size) chunks.push(e.data); };
      var stopped = new Promise(function (res) { rec.onstop = res; });
      rec.start();
      var started = performance.now(), lastVoice = started, speech = false;
      return new Promise(function (resolve) {
        var tick = function () {
          if (rec.state !== 'recording') { resolve(); return; }
          var now = performance.now();
          if (currentAmp > 0.05) { speech = true; lastVoice = now; }
          if (now - started > maxMs || (speech && now - lastVoice > silenceMs)) {
            try { rec.stop(); } catch (e) {}
            resolve(); return;
          }
          setTimeout(tick, 100);
        };
        tick();
      }).then(function () {
        return stopped;
      }).then(function () {
        stopAmp();
        stream.getTracks().forEach(function (t) { t.stop(); });
        activeStream = null;
        return new Blob(chunks, { type: (chunks[0] && chunks[0].type) || 'audio/webm' });
      });
    });
  }

  function blobToWav(blob) {
    return blob.arrayBuffer().then(function (ab) {
      var Ctx = global.AudioContext || global.webkitAudioContext;
      var ctx = new Ctx();
      return ctx.decodeAudioData(ab).then(function (buf) {
        ctx.close();
        return audioBufferToWav16k(buf);
      });
    });
  }

  /* ---------- Sarvam STT / TTS ---------- */

  function sarvamTranscribe(wavBlob) {
    var fd = new FormData();
    fd.append('file', wavBlob, 'audio.wav');
    fd.append('model', 'saarika:v2.5');
    fd.append('language_code', 'unknown'); // auto-detect the spoken language
    return fetch('https://api.sarvam.ai/speech-to-text', {
      method: 'POST',
      headers: { 'api-subscription-key': getKey() },
      body: fd
    }).then(function (r) {
      if (!r.ok) throw new Error('STT ' + r.status);
      return r.json();
    }).then(function (j) {
      return { transcript: (j.transcript || '').trim(), lang: j.language_code || LANG };
    });
  }

  // Sarvam text translation — used to bridge any Indian language to the English
  // NLU (understanding) and English replies back to the user's language (speech).
  function translate(text, source, target) {
    if (!text || !getKey() || source === target) return Promise.resolve(text);
    return fetch('https://api.sarvam.ai/translate', {
      method: 'POST',
      headers: { 'api-subscription-key': getKey(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ input: text, source_language_code: source, target_language_code: target })
    }).then(function (r) {
      if (!r.ok) throw new Error('translate ' + r.status);
      return r.json();
    }).then(function (j) { return j.translated_text || text; }).catch(function () { return text; });
  }

  function sarvamSynthesize(text, lang) {
    return fetch('https://api.sarvam.ai/text-to-speech', {
      method: 'POST',
      headers: { 'api-subscription-key': getKey(), 'Content-Type': 'application/json' },
      body: JSON.stringify({
        inputs: [text.slice(0, 480)],
        target_language_code: lang || LANG,
        speaker: SPEAKER,
        pitch: 0, pace: 1.0, loudness: 1.0,
        speech_sample_rate: 22050,
        enable_preprocessing: true,
        model: 'bulbul:v2'
      })
    }).then(function (r) {
      if (!r.ok) throw new Error('TTS ' + r.status);
      return r.json();
    }).then(function (j) {
      var b64 = j.audios && j.audios[0];
      if (!b64) throw new Error('TTS empty');
      return b64;
    });
  }

  function playBase64Wav(b64) {
    return new Promise(function (resolve) {
      var audio = new Audio('data:audio/wav;base64,' + b64);
      currentAudio = audio;
      // best-effort amplitude off the playing element
      try {
        var Ctx = global.AudioContext || global.webkitAudioContext;
        ampCtx = new Ctx();
        var src = ampCtx.createMediaElementSource(audio);
        var an = ampCtx.createAnalyser(); an.fftSize = 512;
        src.connect(an); an.connect(ampCtx.destination);
        analyser = an; runAmpLoop();
      } catch (e) {}
      audio.onended = function () { stopAmp(); currentAudio = null; resolve(); };
      audio.onerror = function () { stopAmp(); currentAudio = null; resolve(); };
      audio.play().catch(function () { stopAmp(); currentAudio = null; resolve(); });
    });
  }

  /* ---------- Web Speech fallbacks ---------- */

  function webSpeechListen() {
    return new Promise(function (resolve) {
      var SR = global.SpeechRecognition || global.webkitSpeechRecognition;
      if (!SR) { resolve(null); return; }
      var r = new SR();
      recognition = r;
      r.lang = bcp47(LANG);
      r.interimResults = false;
      r.maxAlternatives = 1;
      var result = null;
      r.onresult = function (e) { result = e.results[0][0].transcript; };
      r.onerror = function () {};
      r.onend = function () { recognition = null; resolve(result); };
      try { r.start(); } catch (e) { recognition = null; resolve(null); }
    });
  }

  function pickVoice(lang) {
    try {
      var voices = global.speechSynthesis.getVoices() || [];
      return voices.find(function (v) { return v.lang === lang; }) ||
             voices.find(function (v) { return v.lang && v.lang.indexOf('en-IN') === 0; }) ||
             voices.find(function (v) { return v.lang && v.lang.indexOf('en') === 0; }) || null;
    } catch (e) { return null; }
  }

  function webSpeak(text, lang) {
    return new Promise(function (resolve) {
      if (!global.speechSynthesis) { resolve(); return; }
      var u = new SpeechSynthesisUtterance(text);
      u.lang = bcp47(lang || LANG);
      var v = pickVoice(u.lang); if (v) u.voice = v;
      var done = false;
      var finish = function () { if (done) return; done = true; resolve(); };
      u.onend = finish;
      u.onerror = finish;
      // Safety net: some browsers never fire onend — resolve on an estimated cap.
      var capMs = Math.min(20000, 1200 + text.length * 70);
      setTimeout(finish, capMs);
      try { global.speechSynthesis.cancel(); global.speechSynthesis.speak(u); }
      catch (e) { finish(); }
    });
  }

  /* ---------- public API ---------- */

  function isEnglish(lang) { return !lang || lang.indexOf('en') === 0; }

  // Listen -> returns { display, text, lang }:
  //   display = what the user said (their language, for the transcript)
  //   text    = English text for the NLU (translated if needed)
  //   lang    = detected/used language (so replies can match it)
  function listen() {
    if (getKey()) {
      return recordUntilSilence().then(blobToWav).then(sarvamTranscribe).then(function (res) {
        var spoken = res.transcript;
        var lang = res.lang || LANG;
        if (!spoken) return { display: '', text: '', lang: lang };
        if (isEnglish(lang)) return { display: spoken, text: spoken, lang: 'en-IN' };
        return translate(spoken, lang, 'en-IN').then(function (en) {
          return { display: spoken, text: en, lang: lang };
        });
      }).catch(function () {
        stopAmp();
        return webSpeechListen().then(function (t) { return { display: t, text: t, lang: LANG }; });
      });
    }
    return webSpeechListen().then(function (t) { return { display: t, text: t, lang: LANG }; });
  }

  // Speak English `text`, but voiced in `lang` (translating first if needed).
  // Resolves when finished. Falls back automatically.
  function speak(text, lang) {
    if (!text) return Promise.resolve();
    lang = lang || LANG;
    if (getKey()) {
      var prep = isEnglish(lang) ? Promise.resolve(text) : translate(text, 'en-IN', lang);
      return prep.then(function (out) {
        return sarvamSynthesize(out, lang).then(playBase64Wav);
      }).catch(function () { return webSpeak(text, lang); });
    }
    return webSpeak(text, lang);
  }

  function stop() {
    if (recognition) { try { recognition.abort(); } catch (e) {} recognition = null; }
    if (global.speechSynthesis) { try { global.speechSynthesis.cancel(); } catch (e) {} }
    if (currentAudio) { try { currentAudio.pause(); } catch (e) {} currentAudio = null; }
    if (activeStream) { activeStream.getTracks().forEach(function (t) { t.stop(); }); activeStream = null; }
    stopAmp();
  }

  global.LendVoice = {
    listen: listen,
    speak: speak,
    stop: stop,
    getAmplitude: getAmplitude,
    isSupported: isSupported,
    canListen: canListen,
    hasKey: function () { return !!getKey(); },
    setKey: setKey,
    getKey: getKey,
    setLang: setLang,
    getLang: function () { return LANG; },
    setSpeaker: setSpeaker,
    translate: translate
  };
})(typeof window !== 'undefined' ? window : this);
