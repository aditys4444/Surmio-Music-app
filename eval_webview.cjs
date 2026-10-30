const http = require('http');

async function main() {
  const expr = process.argv[2] || 'window.location.href';
  
  // 1. Get devtools target
  const listRes = await fetch('http://127.0.0.1:9222/json');
  const targets = await listRes.json();
  const page = targets.find(t => t.type === 'page' && t.url.includes('localhost'));
  if (!page || !page.webSocketDebuggerUrl) {
    console.error('No matching page found:', targets);
    process.exit(1);
  }

  // 2. Connect WebSocket
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    ws.onopen = resolve;
    ws.onerror = reject;
  });

  // 3. Send Runtime.evaluate
  const msg = {
    id: 1,
    method: 'Runtime.evaluate',
    params: {
      expression: expr,
      returnByValue: true,
      awaitPromise: true
    }
  };

  ws.send(JSON.stringify(msg));

  ws.onmessage = (event) => {
    const data = JSON.parse(event.data);
    if (data.id === 1) {
      if (data.result && data.result.exceptionDetails) {
        console.error('Exception:', data.result.exceptionDetails);
      } else {
        console.log('Result:', JSON.stringify(data.result?.result?.value, null, 2));
      }
      ws.close();
      process.exit(0);
    }
  };
}

main().catch(err => {
  console.error(err);
  process.exit(1);
});
