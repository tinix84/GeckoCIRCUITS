import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { CssBaseline, ThemeProvider, createTheme } from '@mui/material';
import { App } from './App';
import { SessionProvider } from './session';
import './style.css';

const theme = createTheme({
  palette: {
    primary: { main: '#116657' },
    background: { default: '#f3f6f4', paper: '#ffffff' },
    text: { primary: '#182923', secondary: '#50645c' },
  },
  typography: { fontFamily: 'Inter, system-ui, sans-serif', h1: { fontSize: '2rem', fontWeight: 700 } },
  shape: { borderRadius: 10 },
  components: { MuiButton: { styleOverrides: { root: { textTransform: 'none' } } } },
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <BrowserRouter>
        <SessionProvider><App /></SessionProvider>
      </BrowserRouter>
    </ThemeProvider>
  </StrictMode>,
);
