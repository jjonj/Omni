const net = require('net');
const port = 0; // Let OS pick, or use fixed like 12345

console.log(`Attempting to create TCP server on port: ${port}`);

const server = net.createServer((socket) => {
    console.log('Client connected');
});

server.on('error', (err) => {
    console.error('Server error:', err);
});

try {
    server.listen(port, '127.0.0.1', () => {
        const addr = server.address();
        console.log(`Server listening successfully on ${addr.address}:${addr.port}`);
        setTimeout(() => {
            server.close();
            console.log('Server closed.');
        }, 1000);
    });
} catch (err) {
    console.error('Exception:', err);
}
