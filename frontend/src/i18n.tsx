import { createContext, useContext } from 'react';
export type Language = 'fa' | 'en';
export const LanguageContext = createContext<Language>('fa');
export function useText() {
  const language = useContext(LanguageContext);
  return (english: string, persian: string) => language === 'fa' ? persian : english;
}
