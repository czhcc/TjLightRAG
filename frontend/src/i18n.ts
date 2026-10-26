import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
void i18n.use(initReactI18next).init({lng:localStorage.getItem('language') || 'zh',fallbackLng:'zh',interpolation:{escapeValue:false},resources:{zh:{translation:{username:'用户名',password:'密码',login:'登录',logout:'注销',welcome:'欢迎回来',subtitle:'登录您的 TjLightRAG 工作空间',error:'用户名或密码错误',network:'连接失败，请稍后重试',loading:'加载中…'}},en:{translation:{username:'Username',password:'Password',login:'Sign in',logout:'Sign out',welcome:'Welcome back',subtitle:'Sign in to your TjLightRAG workspace',error:'Invalid username or password',network:'Connection failed. Please retry.',loading:'Loading…'}}}});
export default i18n;
