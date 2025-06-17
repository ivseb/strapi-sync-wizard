import { Express } from 'express';
import { createProxyMiddleware } from 'http-proxy-middleware';

module.exports = function(app: Express) {
    app.use(
        '/api',
        createProxyMiddleware({
            target: 'http://localhost:8080',
            changeOrigin: true,
            logLevel: 'debug'
        })
    );
};
