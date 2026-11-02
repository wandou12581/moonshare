(() => {
    'use strict';

    const canvas = document.getElementById('screen');
    const ctx = canvas.getContext('2d', { alpha: true, desynchronized: true });
    const idle = document.getElementById('idle');
    const idleHint = document.getElementById('idleHint');
    const errorPanel = document.getElementById('errorPanel');
    const errorTitle = document.getElementById('errorTitle');
    const errorText = document.getElementById('errorText');
    const stateLabel = document.getElementById('stateLabel');
    const deviceName = document.getElementById('deviceName');
    const resolution = document.getElementById('resolution');
    const fpsLabel = document.getElementById('fps');
    const bitrateLabel = document.getElementById('bitrate');
    const queueLabel = document.getElementById('queue');
    const audioStatus = document.getElementById('audioStatus');
    const toast = document.getElementById('toast');

    let ws = null;
    let audioWs = null;
    let audioCtx = null;
    let audioNextAt = 0;
    let audioFrames = 0;
    let audioFramesThisSecond = 0;
    let reconnectTimer = null;
    let decoder = null;
    let decoderCodec = null;
    let keySeen = false;
    let streamWidth = 0;
    let streamHeight = 0;
    let decodedFrames = 0;
    let lastStatsAt = performance.now();
    let lastKeyRequestAt = 0;
    let lastFrameAt = 0;
    let errorCount = 0;
    let errorWindowStart = 0;
    let hideTimer = null;
    let toastTimer = null;

    const dpr = () => Math.min(window.devicePixelRatio || 1, 2.5);

    function resizeCanvas() {
        const width = Math.max(1, Math.round(canvas.clientWidth * dpr()));
        const height = Math.max(1, Math.round(canvas.clientHeight * dpr()));
        if (canvas.width !== width || canvas.height !== height) {
            canvas.width = width;
            canvas.height = height;
            ctx.setTransform(dpr(), 0, 0, dpr(), 0, 0);
            ctx.imageSmoothingEnabled = true;
        }
    }
    window.addEventListener('resize', resizeCanvas);
    resizeCanvas();

    function setBodyState(state) {
        document.body.classList.remove('idle', 'live', 'error');
        if (state === 'idle' || state === 'connecting' || state === 'waiting') {
            document.body.classList.add('idle');
            idle.classList.remove('hidden');
        } else if (state === 'live') {
            document.body.classList.add('live');
            idle.classList.add('hidden');
        } else if (state === 'error') {
            document.body.classList.add('error');
            idle.classList.add('hidden');
        }
    }

    function setState(state, message, device) {
        setBodyState(state);
        if (device) deviceName.textContent = device;
        switch (state) {
            case 'idle':
                stateLabel.textContent = '空闲';
                idleHint.textContent = '在左侧输入手机屏幕上的配对码，然后点击“连接并观看”。';
                break;
            case 'connecting':
                stateLabel.textContent = '连接中';
                idleHint.textContent = message || '正在与手机建立加密会话…';
                break;
            case 'waiting':
                stateLabel.textContent = '已配对';
                idleHint.textContent = message || '已配对，等待手机发送第一帧画面…';
                break;
            case 'live':
                stateLabel.textContent = '直播';
                idleHint.textContent = '正在接收画面';
                break;
            case 'error':
                stateLabel.textContent = '错误';
                break;
        }
        if (state !== 'error') {
            errorPanel.classList.add('hidden');
        }
    }

    function showError(title, message) {
        setState('error', message);
        errorTitle.textContent = title;
        errorText.textContent = message;
        errorPanel.classList.remove('hidden');
    }

    function showToast(message) {
        toast.textContent = message;
        toast.classList.add('visible');
        clearTimeout(toastTimer);
        toastTimer = setTimeout(() => toast.classList.remove('visible'), 2400);
    }

    function send(payload) {
        if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify(payload));
        }
    }

    function requestKey(reason) {
        const now = performance.now();
        if (now - lastKeyRequestAt < 800) return;
        lastKeyRequestAt = now;
        send({ type: 'need-key' });
        if (reason) showToast(reason);
    }

    function findSps(data) {
        for (let i = 0; i + 4 < data.length; i++) {
            if (data[i] === 0 && data[i + 1] === 0 && data[i + 2] === 1) {
                const nalType = data[i + 3] & 0x1f;
                if (nalType === 7) return i + 3;
                i += 3;
            }
        }
        return -1;
    }

    function codecStringFromSps(data) {
        const offset = findSps(data);
        if (offset < 0 || offset + 3 >= data.length) return null;
        const hex = (value) => value.toString(16).padStart(2, '0').toUpperCase();
        return 'avc1.' + hex(data[offset + 1]) + hex(data[offset + 2]) + hex(data[offset + 3]);
    }

    function closeDecoder() {
        if (!decoder) return;
        try {
            if (decoder.state !== 'closed') decoder.close();
        } catch (_) {
            // ignore
        }
        decoder = null;
        decoderCodec = null;
        keySeen = false;
    }

    function createDecoder(data) {
        closeDecoder();
        const parsed = codecStringFromSps(data);
        const candidates = [];
        if (parsed) candidates.push(parsed);
        candidates.push('avc1.42E01E', 'avc1.4D401F', 'avc1.64001F');

        for (const codec of candidates) {
            try {
                const candidate = new VideoDecoder({
                    output: onDecodedFrame,
                    error: onDecoderError
                });
                candidate.configure({
                    codec,
                    optimizeForLatency: true,
                    hardwareAcceleration: 'prefer-hardware'
                });
                decoder = candidate;
                decoderCodec = codec;
                keySeen = false;
                return true;
            } catch (error) {
                console.warn('VideoDecoder configure failed for', codec, error);
                try { candidate.close(); } catch (_) { /* ignore */ }
            }
        }
        showError('浏览器不支持 H.264 解码', '请更新 Microsoft Edge WebView2 运行时，或确认系统支持 H.264 硬件解码。');
        return false;
    }

    function onDecodedFrame(frame) {
        try {
            resizeCanvas();
            const cw = canvas.clientWidth;
            const ch = canvas.clientHeight;
            const vw = frame.displayWidth || streamWidth || 16;
            const vh = frame.displayHeight || streamHeight || 9;
            const scale = Math.min(cw / vw, ch / vh);
            const dw = vw * scale;
            const dh = vh * scale;
            const dx = (cw - dw) / 2;
            const dy = (ch - dh) / 2;
            ctx.clearRect(0, 0, cw, ch);
            ctx.fillStyle = '#000';
            ctx.fillRect(dx, dy, dw, dh);
            ctx.drawImage(frame, dx, dy, dw, dh);
            if (!streamWidth || !streamHeight) {
                streamWidth = vw;
                streamHeight = vh;
                resolution.textContent = `${vw} × ${vh}`;
            }
            decodedFrames++;
            lastFrameAt = performance.now();
            errorCount = 0;
            if (!document.body.classList.contains('live')) {
                setBodyState('live');
            }
            if (stateLabel.textContent === '画面静止' || stateLabel.textContent === '缓冲中') {
                stateLabel.textContent = '直播';
            }
        } catch (error) {
            console.error(error);
        } finally {
            frame.close();
        }
    }

    function onDecoderError(error) {
        console.error('decoder error', error);
        const now = performance.now();
        if (now - errorWindowStart > 5000) {
            errorWindowStart = now;
            errorCount = 0;
        }
        errorCount++;
        closeDecoder();
        requestKey('画面丢失，正在请求关键帧…');
        if (errorCount >= 3) {
            showError('画面解码失败', error && error.message ? error.message : '解码器无法恢复，请尝试重新连接手机。');
        }
    }

    function feedFrame(data, keyFrame, timestamp) {
        if (keyFrame) {
            const parsed = codecStringFromSps(data);
            const needsNewDecoder = !decoder || (parsed && parsed !== decoderCodec);
            if (needsNewDecoder) {
                if (!createDecoder(data)) return;
            }
            keySeen = true;
        }
        if (!keySeen || !decoder) {
            requestKey('等待关键帧…');
            return;
        }
        if (decoder.decodeQueueSize > 10 && !keyFrame) {
            return;
        }
        try {
            decoder.decode(new EncodedVideoChunk({
                type: keyFrame ? 'key' : 'delta',
                timestamp,
                data
            }));
        } catch (error) {
            onDecoderError(error);
        }
    }

    function handleBinary(buffer) {
        if (!(buffer instanceof ArrayBuffer) || buffer.byteLength < 17) return;
        const view = new DataView(buffer);
        if (view.getUint8(0) !== 0x4d || view.getUint8(1) !== 0x53 ||
            view.getUint8(2) !== 0x46 || view.getUint8(3) !== 0x31) {
            return;
        }
        const keyFrame = (view.getUint8(4) & 1) !== 0;
        const timestamp = Number(view.getBigInt64(8, true));
        const data = new Uint8Array(buffer, 16);
        feedFrame(data, keyFrame, timestamp);
    }

    function handleText(text) {
        let message;
        try {
            message = JSON.parse(text);
        } catch (_) {
            return;
        }
        switch (message.type) {
            case 'state':
                setState(message.state || 'idle', message.message, message.device);
                break;
            case 'stream':
                streamWidth = message.width || streamWidth;
                streamHeight = message.height || streamHeight;
                if (streamWidth && streamHeight) resolution.textContent = `${streamWidth} × ${streamHeight}`;
                break;
            case 'stats':
                if (typeof message.kbps === 'number') {
                    bitrateLabel.textContent = `${(message.kbps / 1000).toFixed(1)} Mbps`;
                }
                if (typeof message.fps === 'number' && message.fps > 0) {
                    fpsLabel.textContent = `${message.fps.toFixed(1)} fps`;
                }
                break;
            case 'error':
                showError('连接错误', message.message || '未知错误');
                break;
        }
    }

    function ensureAudio() {
        if (!audioCtx) {
            try {
                audioCtx = new AudioContext({ sampleRate: 48000, latencyHint: 'interactive' });
            } catch (_) {
                return;
            }
        }
        if (audioCtx.state === 'suspended') {
            audioCtx.resume().catch(() => {});
        }
        connectAudio();
    }

    function connectAudio() {
        if (audioWs && (audioWs.readyState === WebSocket.OPEN ||
            audioWs.readyState === WebSocket.CONNECTING)) return;
        const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
        audioWs = new WebSocket(`${protocol}//${location.host}/audio`);
        audioWs.binaryType = 'arraybuffer';
        audioWs.onmessage = (event) => {
            if (event.data instanceof ArrayBuffer) playPcm(event.data);
        };
        audioWs.onclose = () => {
            audioWs = null;
            setTimeout(() => {
                if (audioCtx) connectAudio();
            }, 900);
        };
        audioWs.onerror = () => {
            try { audioWs.close(); } catch (_) { /* ignore */ }
        };
    }

    function playPcm(buffer) {
        if (!audioCtx) return;
        if (audioCtx.state === 'suspended') audioCtx.resume().catch(() => {});
        const samples = Math.floor(buffer.byteLength / 4);
        if (samples <= 0) return;
        const pcm = new Int16Array(buffer);
        const frames = Math.floor(pcm.length / 2);
        if (frames <= 0) return;
        const audioBuffer = audioCtx.createBuffer(2, frames, 48000);
        const left = audioBuffer.getChannelData(0);
        const right = audioBuffer.getChannelData(1);
        for (let i = 0; i < frames; i++) {
            left[i] = pcm[i * 2] / 32768;
            right[i] = pcm[i * 2 + 1] / 32768;
        }
        const now = audioCtx.currentTime;
        if (audioNextAt < now + 0.04 || audioNextAt > now + 0.75) {
            audioNextAt = now + 0.04;
        }
        const source = audioCtx.createBufferSource();
        source.buffer = audioBuffer;
        source.connect(audioCtx.destination);
        source.start(audioNextAt);
        audioNextAt += audioBuffer.duration;
        audioFrames++;
        audioFramesThisSecond++;
    }

    window.addEventListener('pointerdown', ensureAudio, { once: true });
    audioStatus.addEventListener('click', (event) => {
        event.stopPropagation();
        ensureAudio();
    });

    function connect() {
        if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
        clearTimeout(reconnectTimer);
        const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
        ws = new WebSocket(`${protocol}//${location.host}/ws`);
        ws.binaryType = 'arraybuffer';
        ws.onopen = () => {
            send({ type: 'ready' });
            ensureAudio();
            if (!decoder) setState('idle');
        };
        ws.onmessage = (event) => {
            if (typeof event.data === 'string') handleText(event.data);
            else handleBinary(event.data);
        };
        ws.onclose = () => {
            ws = null;
            closeDecoder();
            setState('connecting', '本地查看器已断开，正在重连…');
            reconnectTimer = setTimeout(connect, 900);
        };
        ws.onerror = () => {
            try { ws.close(); } catch (_) { /* ignore */ }
        };
    }

    function tick() {
        resizeCanvas();
        const now = performance.now();
        if (now - lastStatsAt >= 1000) {
            lastStatsAt = now;
            const elapsed = Math.max(0.2, (now - (tick.lastAt || now - 1000)) / 1000);
            tick.lastAt = now;
            const fps = decodedFrames / elapsed;
            decodedFrames = 0;
            if (fps > 0) fpsLabel.textContent = `${fps.toFixed(1)} fps`;
            if (audioCtx) {
                const audioState = audioCtx.state === 'running' ? '开' : '待点击';
                audioStatus.textContent = `音频 ${audioState} · ${audioFramesThisSecond}/s`;
            }
            audioFramesThisSecond = 0;
            queueLabel.textContent = `decode ${decoder ? decoder.decodeQueueSize : 0}`;
            send({
                type: 'stats',
                decodeFps: Math.round(fps * 10) / 10,
                decodeQueue: decoder ? decoder.decodeQueueSize : -1
            });
        }
        if (document.body.classList.contains('live') && now - lastFrameAt > 3000) {
            stateLabel.textContent = '画面静止';
        }
        requestAnimationFrame(tick);
    }
    requestAnimationFrame(tick);

    function toggleFullscreen() {
        if (!document.fullscreenElement) {
            document.documentElement.requestFullscreen().catch(() => {
                showToast('无法进入全屏');
            });
        } else {
            document.exitFullscreen().catch(() => {});
        }
    }

    document.getElementById('fullscreenButton').addEventListener('click', toggleFullscreen);
    document.getElementById('retryButton').addEventListener('click', () => {
        errorPanel.classList.add('hidden');
        requestKey('正在请求关键帧…');
    });
    canvas.addEventListener('dblclick', toggleFullscreen);
    document.addEventListener('fullscreenchange', () => {
        document.body.classList.toggle('fullscreen', !!document.fullscreenElement);
    });
    document.addEventListener('contextmenu', (event) => event.preventDefault());
    window.addEventListener('keydown', (event) => {
        if (event.key === 'F11') {
            event.preventDefault();
            if (window.chrome && window.chrome.webview) {
                window.chrome.webview.postMessage({ type: 'toggle-fullscreen' });
            } else {
                toggleFullscreen();
            }
        }
    });
    document.addEventListener('mousemove', () => {
        document.body.classList.add('show-chrome');
        clearTimeout(hideTimer);
        hideTimer = setTimeout(() => {
            document.body.classList.remove('show-chrome');
            if (document.body.classList.contains('fullscreen')) {
                document.body.classList.add('hide-cursor');
            }
        }, 2200);
        document.body.classList.remove('hide-cursor');
    });

    window.addEventListener('beforeunload', () => {
        if (ws) ws.close();
        closeDecoder();
    });

    if (typeof VideoDecoder === 'undefined') {
        showError('浏览器不支持 WebCodecs', '请安装或更新 Microsoft Edge WebView2 运行时（109 或更高版本）。');
        document.getElementById('idleHint').textContent = '此环境缺少 WebCodecs H.264 解码能力。';
    } else {
        setState('idle');
        connect();
    }
})();
