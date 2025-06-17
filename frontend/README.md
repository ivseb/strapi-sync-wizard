# Strapi Sync Frontend

This is the frontend for the Strapi Sync application, built with React and TypeScript.

## Setup

1. Install dependencies:
   ```
   cd frontend
   npm install
   ```

## Development

To start the development server:
```
npm start
```

This will start the React development server at http://localhost:3000.

## Building for Production

To build the frontend for production:
```
npm run build
```

This will:
1. Build the React app
2. Remove any existing files in the Ktor static resources directory
3. Copy the build output to the Ktor static resources directory

After building, you can run the Ktor application to serve the React app.

## Project Structure

- `src/` - Source code for the React application
  - `components/` - React components
  - `App.tsx` - Main application component
  - `index.tsx` - Entry point for the React application
- `public/` - Static assets and HTML template

## Notes

- The React app is configured to be served as a Single Page Application (SPA) by the Ktor backend.
- API requests are proxied to the Ktor backend during development.
- In production, the Ktor backend serves the React app from the `static` directory in the resources.