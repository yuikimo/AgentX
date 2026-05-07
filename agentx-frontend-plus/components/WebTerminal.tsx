"use client";

import React, { useEffect, useRef, useState } from 'react';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { WebLinksAddon } from '@xterm/addon-web-links';
import '@xterm/xterm/css/xterm.css';

interface WebTerminalProps {
  containerId: string;
  containerName: string;
  onClose?: () => void;
}

export default function WebTerminal({ containerId, containerName, onClose }: WebTerminalProps) {
  const rootRef = useRef<HTMLDivElement>(null);
  const terminalRef = useRef<HTMLDivElement>(null);
  const terminal = useRef<Terminal | null>(null);
  const websocket = useRef<WebSocket | null>(null);
  const fitAddon = useRef<FitAddon | null>(null);
  const [isConnected, setIsConnected] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [connectionStatus, setConnectionStatus] = useState('正在连接...');

  const fitTerminal = () => {
    if (!fitAddon.current || !terminal.current) return;

    try {
      fitAddon.current.fit();
    } catch (error) {
 
    }
  };

  const scheduleFit = () => {
    requestAnimationFrame(() => {
      fitTerminal();
      setTimeout(fitTerminal, 100);
      setTimeout(fitTerminal, 300);
    });
  };

  useEffect(() => {
    if (!terminalRef.current) return;

    const initializeTerminal = () => {
      // 确保容器有有效的尺寸
      const container = terminalRef.current;
      if (!container || container.offsetWidth === 0 || container.offsetHeight === 0) {
        // 如果容器还没有尺寸，稍后重试
        setTimeout(initializeTerminal, 50);
        return;
      }

      // 初始化终端
      terminal.current = new Terminal({
        cursorBlink: true,
        fontSize: 14,
        fontFamily: 'Monaco, Menlo, "Ubuntu Mono", monospace',
        theme: {
          background: '#1e1e1e',
          foreground: '#d4d4d4',
          cursor: '#ffffff',
          selection: '#264f78',
          black: '#000000',
          red: '#cd3131',
          green: '#0dbc79',
          yellow: '#e5e510',
          blue: '#2472c8',
          magenta: '#bc3fbc',
          cyan: '#11a8cd',
          white: '#e5e5e5',
          brightBlack: '#666666',
          brightRed: '#f14c4c',
          brightGreen: '#23d18b',
          brightYellow: '#f5f543',
          brightBlue: '#3b8eea',
          brightMagenta: '#d670d6',
          brightCyan: '#29b8db',
          brightWhite: '#ffffff'
        },
        cols: 80,
        rows: 24
      });

      // 添加插件
      fitAddon.current = new FitAddon();
      terminal.current.loadAddon(fitAddon.current);
      terminal.current.loadAddon(new WebLinksAddon());

      // 挂载到DOM
      terminal.current.open(container);
      
      // 延迟执行fit以确保DOM已完全渲染
      setTimeout(fitTerminal, 200);

      // 连接WebSocket
      connectWebSocket();
    };

    // 开始初始化
    initializeTerminal();

    // 处理窗口大小调整
    const handleResize = () => scheduleFit();
    const handleFullscreenChange = () => {
      setIsFullscreen(document.fullscreenElement === rootRef.current);
      scheduleFit();
    };
    const resizeObserver = new ResizeObserver(() => scheduleFit());

    if (rootRef.current) {
      resizeObserver.observe(rootRef.current);
    }
    if (terminalRef.current) {
      resizeObserver.observe(terminalRef.current);
    }

    window.addEventListener('resize', handleResize);
    document.addEventListener('fullscreenchange', handleFullscreenChange);

    return () => {
      window.removeEventListener('resize', handleResize);
      document.removeEventListener('fullscreenchange', handleFullscreenChange);
      resizeObserver.disconnect();
      if (websocket.current) {
        websocket.current.close();
      }
      if (terminal.current) {
        terminal.current.dispose();
      }
    };
  }, [containerId]);

  const connectWebSocket = () => {
    const getWebSocketUrl = () => {
      const configuredBackendPort = process.env.NEXT_PUBLIC_BACKEND_PORT || '8088';
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const shouldDirectConnect =
        window.location.hostname === 'localhost' ||
        window.location.hostname === '127.0.0.1' ||
        window.location.port === '3000';
      const port = shouldDirectConnect
        ? configuredBackendPort
        : window.location.port;
      const host = `${window.location.hostname}${port ? `:${port}` : ''}`;

      return `${protocol}//${host}/api/ws/terminal?containerId=${containerId}`;
    };
    
    const wsUrl = getWebSocketUrl();
 
    
    websocket.current = new WebSocket(wsUrl);

    websocket.current.onopen = () => {
      setIsConnected(true);
      setConnectionStatus('已连接');
      
      if (terminal.current) {
        terminal.current.clear();
        terminal.current.writeln('\x1b[32m✓ 终端连接成功\x1b[0m');
        terminal.current.writeln(`容器: ${containerName} (${containerId})`);
        terminal.current.writeln('');
        
        // 监听终端输入
        terminal.current.onData((data) => {
          if (websocket.current && websocket.current.readyState === WebSocket.OPEN) {
            websocket.current.send(JSON.stringify({
              type: 'input',
              data: data
            }));
          }
        });
      }
    };

    websocket.current.onmessage = (event) => {
      if (terminal.current) {
        terminal.current.write(event.data);
      }
    };

    websocket.current.onclose = (event) => {
      setIsConnected(false);
      setConnectionStatus('连接已断开');
      
      if (terminal.current) {
        terminal.current.writeln('\r\n\x1b[31m✗ 终端连接已断开\x1b[0m');
        
        if (event.code !== 1000) {
          terminal.current.writeln(`错误代码: ${event.code}, 原因: ${event.reason || '未知'}`);
        }
      }
    };

    websocket.current.onerror = (error) => {
      setIsConnected(false);
      setConnectionStatus('连接失败');
      
      if (terminal.current) {
        terminal.current.writeln('\r\n\x1b[31m✗ 终端连接失败\x1b[0m');
        terminal.current.writeln('请检查容器是否正在运行');
      }
    };
  };

  const handleReconnect = () => {
    if (websocket.current) {
      websocket.current.close();
    }
    setConnectionStatus('正在重连...');
    setTimeout(connectWebSocket, 1000);
  };

  const handleFullscreen = () => {
    if (rootRef.current) {
      if (document.fullscreenElement === rootRef.current) {
        document.exitFullscreen();
      } else {
        rootRef.current.requestFullscreen();
      }
    }
  };

  return (
    <div ref={rootRef} className="flex h-full min-h-0 flex-col bg-gray-900 fullscreen:h-screen fullscreen:w-screen">
      {/* 终端工具栏 */}
      <div className="flex shrink-0 items-center justify-between p-3 bg-gray-800 border-b border-gray-700">
        <div className="flex items-center space-x-3">
          <div className="flex items-center space-x-2">
            <div className={`w-3 h-3 rounded-full ${isConnected ? 'bg-green-500' : 'bg-red-500'}`}></div>
            <span className="text-sm text-gray-300">{connectionStatus}</span>
          </div>
          <div className="text-sm text-gray-400">
            {containerName} ({containerId.substring(0, 12)})
          </div>
        </div>
        
        <div className="flex items-center space-x-2">
          {!isConnected && (
            <button
              onClick={handleReconnect}
              className="px-3 py-1 text-xs bg-blue-600 text-white rounded hover:bg-blue-700 transition-colors"
            >
              重连
            </button>
          )}
          <button
            onClick={handleFullscreen}
            className="px-3 py-1 text-xs bg-gray-600 text-white rounded hover:bg-gray-700 transition-colors"
          >
            {isFullscreen ? '退出全屏' : '全屏'}
          </button>
          {onClose && (
            <button
              onClick={onClose}
              className="px-3 py-1 text-xs bg-red-600 text-white rounded hover:bg-red-700 transition-colors"
            >
              关闭
            </button>
          )}
        </div>
      </div>

      {/* 终端区域 */}
      <div className="min-h-0 flex-1 p-4 bg-gray-900">
        <div 
          ref={terminalRef} 
          className="w-full h-full"
          style={{ 
            minHeight: 0,
            width: '100%',
            height: '100%'
          }}
        />
      </div>

      {/* 终端提示 */}
      <div className="shrink-0 p-2 bg-gray-800 border-t border-gray-700">
        <div className="text-xs text-gray-400 flex flex-wrap gap-4">
          <span>Ctrl+C: 中断命令</span>
          <span>Ctrl+D: 退出</span>
          <span>clear: 清屏</span>
          <span>exit: 退出终端</span>
        </div>
      </div>
    </div>
  );
}
