// Typographic posters generated from the event title. Events carry no image yet,
// so each card gets a stable colour tone and a display word derived from its data.
export interface PosterArt { tone: string; word: string; mark: string }

const TONES = 6

function hash(text: string) {
  let h = 0
  for (const ch of text) h = (h * 31 + ch.codePointAt(0)!) >>> 0
  return h
}

export function posterArt(id: string, title: string): PosterArt {
  const latin = (title.match(/[A-Za-z][A-Za-z'&.-]*/g) || []).filter(w => w.length > 1)
  const cjk = title.replace(/[^㐀-鿿]/g, '')
  const word = latin.length
    ? latin.slice(0, 3).join('\n').toUpperCase()
    : (cjk.slice(0, 4) || title.slice(0, 4)).replace(/(.{2})(?=.)/u, '$1\n')
  const digits = title.match(/\d{2,4}/)
  const mark = digits ? digits[0].slice(-2) : (latin[0]?.[0] || cjk[0] || title[0] || 'E').toUpperCase()
  return { tone: 'tone-' + (hash(id) % TONES), word, mark }
}
