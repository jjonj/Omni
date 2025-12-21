const net = require('net');
const pipeName = '\\.\pipe\gemini-cli-test-' + process.pid;

console.log(`Attempting to create pipe: ${pipeName}`);

const server = net.createServer((socket) => {
    console.log('Client connected');
});

server.on('error', (err) => {
    console.error('Server error:', err);
});

try {
    server.listen(pipeName, () => {
        console.log('Server listening successfully.');
        setTimeout(() => {
            server.close();
            console.log('Server closed.');
        }, 1000);
    });
} catch (err) {
    console.error('Exception:', err);
}
