import {inflateSync} from 'node:zlib';

// Baseline PNG and JPEG decoders for the acceptance check. No third-party image library:
// the edge bundle stays dependency-free. Progressive and arithmetic JPEG return null.

// Natural-order index -> zigzag index, ITU T.81 Figure A.6.
const ZIGZAG = [
  0, 1, 5, 6, 14, 15, 27, 28,
  2, 4, 7, 13, 16, 26, 29, 42,
  3, 8, 12, 17, 25, 30, 41, 43,
  9, 11, 18, 24, 31, 40, 44, 53,
  10, 19, 23, 32, 39, 45, 52, 54,
  20, 22, 33, 38, 46, 51, 55, 60,
  21, 34, 37, 47, 50, 56, 59, 61,
  35, 36, 48, 49, 57, 58, 62, 63,
];
const NATURAL_FROM_ZIGZAG = new Array(64);
for (let natural = 0; natural < 64; natural++) NATURAL_FROM_ZIGZAG[ZIGZAG[natural]] = natural;

function paeth(left, up, upperLeft) {
  const estimate = left + up - upperLeft;
  const dl = Math.abs(estimate - left);
  const du = Math.abs(estimate - up);
  const dul = Math.abs(estimate - upperLeft);
  if (dl <= du && dl <= dul) return left;
  if (du <= dul) return up;
  return upperLeft;
}

function decodePNG(bytes) {
  if (bytes.length < 8 || bytes[0] !== 0x89 || bytes[1] !== 0x50 || bytes[2] !== 0x4e || bytes[3] !== 0x47) return null;
  let offset = 8;
  let width = 0;
  let height = 0;
  let colorType = -1;
  let interlace = 0;
  const idat = [];
  while (offset + 8 <= bytes.length) {
    const length = bytes.readUInt32BE(offset);
    const type = bytes.toString('latin1', offset + 4, offset + 8);
    const start = offset + 8;
    const end = start + length;
    if (end + 4 > bytes.length) return null;
    if (type === 'IHDR') {
      if (length < 13) return null;
      width = bytes.readUInt32BE(start);
      height = bytes.readUInt32BE(start + 4);
      const depth = bytes[start + 8];
      colorType = bytes[start + 9];
      interlace = bytes[start + 12];
      if (depth !== 8 || bytes[start + 10] !== 0 || bytes[start + 11] !== 0) return null;
      if (colorType !== 0 && colorType !== 2 && colorType !== 6) return null;
    } else if (type === 'IDAT') {
      idat.push(bytes.subarray(start, end));
    } else if (type === 'IEND') break;
    offset = end + 4;
  }
  if (!width || !height || width > 4096 || height > 4096 || interlace !== 0) return null;
  let inflated;
  try { inflated = inflateSync(Buffer.concat(idat)); } catch { return null; }
  const channels = colorType === 0 ? 1 : colorType === 2 ? 3 : 4;
  const stride = width * channels;
  const expected = height * (stride + 1);
  if (inflated.length < expected) return null;
  const gray = new Float64Array(width * height);
  const previous = Buffer.alloc(stride);
  const current = Buffer.alloc(stride);
  let cursor = 0;
  for (let y = 0; y < height; y++) {
    const filter = inflated[cursor++];
    const scan = inflated.subarray(cursor, cursor + stride);
    cursor += stride;
    for (let i = 0; i < stride; i++) {
      const left = i >= channels ? current[i - channels] : 0;
      const up = previous[i];
      const upperLeft = i >= channels ? previous[i - channels] : 0;
      const raw = scan[i];
      let value = raw;
      if (filter === 1) value = (raw + left) & 255;
      else if (filter === 2) value = (raw + up) & 255;
      else if (filter === 3) value = (raw + ((left + up) >> 1)) & 255;
      else if (filter === 4) value = (raw + paeth(left, up, upperLeft)) & 255;
      else if (filter !== 0) return null;
      current[i] = value;
    }
    for (let x = 0; x < width; x++) {
      const i = x * channels;
      const red = colorType === 0 ? current[i] : current[i];
      const green = colorType === 0 ? current[i] : current[i + 1];
      const blue = colorType === 0 ? current[i] : current[i + 2];
      gray[y * width + x] = (0.299 * red + 0.587 * green + 0.114 * blue) / 255;
    }
    previous.set(current);
  }
  return {width, height, gray};
}

function buildHuffman(counts, symbols) {
  const maxCode = [];
  const minCode = [];
  const index = [];
  let code = 0;
  let symbolIndex = 0;
  for (let length = 1; length <= 16; length++) {
    index[length] = symbolIndex;
    const count = counts[length - 1];
    if (count === 0) {
      maxCode[length] = -1;
      minCode[length] = 0;
    } else {
      minCode[length] = code;
      maxCode[length] = code + count - 1;
      code = maxCode[length] + 1;
    }
    symbolIndex += count;
    code <<= 1;
  }
  return {counts, symbols, maxCode, minCode, index};
}

function readHuffman(tables) {
  const dc = [];
  const ac = [];
  for (const table of tables) {
    const tree = buildHuffman(table.counts, table.symbols);
    if (table.classId === 0) dc[table.id] = tree;
    else ac[table.id] = tree;
  }
  return {dc, ac};
}

function idctBlock(coefficients) {
  const out = new Float64Array(64);
  for (let y = 0; y < 8; y++) {
    for (let x = 0; x < 8; x++) {
      let sum = 0;
      for (let v = 0; v < 8; v++) {
        for (let u = 0; u < 8; u++) {
          const cu = u === 0 ? 0.7071067811865476 : 1;
          const cv = v === 0 ? 0.7071067811865476 : 1;
          sum += cu * cv * coefficients[v * 8 + u]
            * Math.cos(((2 * x + 1) * u * Math.PI) / 16)
            * Math.cos(((2 * y + 1) * v * Math.PI) / 16);
        }
      }
      out[y * 8 + x] = sum / 4;
    }
  }
  return out;
}

class BitReader {
  constructor(bytes, start) {
    this.bytes = bytes;
    this.offset = start;
    this.bit = 0;
    this.current = 0;
    this.ended = false;
  }

  nextByte() {
    if (this.offset >= this.bytes.length) { this.ended = true; return 0; }
    const value = this.bytes[this.offset++];
    if (value !== 0xff) return value;
    if (this.offset >= this.bytes.length) { this.ended = true; return 0; }
    const marker = this.bytes[this.offset++];
    if (marker === 0x00) return 0xff;
    this.offset -= 2;
    this.ended = true;
    return 0;
  }

  readBit() {
    if (this.bit === 0) {
      this.current = this.nextByte();
      this.bit = 8;
      if (this.ended) return 0;
    }
    this.bit -= 1;
    return (this.current >> this.bit) & 1;
  }

  readBits(count) {
    let value = 0;
    for (let i = 0; i < count; i++) value = (value << 1) | this.readBit();
    return value;
  }

  symbol(table) {
    if (!table) return null;
    let code = 0;
    for (let length = 1; length <= 16; length++) {
      code = (code << 1) | this.readBit();
      if (this.ended) return null;
      if (code <= table.maxCode[length] && table.counts[length - 1] > 0 && code >= table.minCode[length]) {
        const at = table.index[length] + (code - table.minCode[length]);
        return table.symbols[at];
      }
    }
    return null;
  }

  receive(category) {
    if (category === 0) return 0;
    const bits = this.readBits(category);
    const threshold = 1 << (category - 1);
    return bits < threshold ? bits - (1 << category) + 1 : bits;
  }
}

function decodeBlock(reader, dcTable, acTable, quant, predictor) {
  if (!quant) return null;
  const dcCategory = reader.symbol(dcTable);
  if (dcCategory == null || dcCategory > 11) return null;
  const dc = predictor + reader.receive(dcCategory);
  const natural = new Float64Array(64);
  natural[0] = dc * quant[0];
  let k = 1;
  while (k < 64) {
    const symbol = reader.symbol(acTable);
    if (symbol == null) return null;
    if (symbol === 0) break;
    if (symbol === 0xf0) {
      k += 16;
      if (k > 64) return null;
      continue;
    }
    const run = symbol >> 4;
    const category = symbol & 15;
    k += run;
    if (k >= 64 || category === 0) return null;
    natural[NATURAL_FROM_ZIGZAG[k]] = reader.receive(category) * quant[k];
    k += 1;
  }
  return {dc, samples: idctBlock(natural)};
}

function decodeJPEG(bytes) {
  if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) return null;
  let offset = 2;
  const quant = [];
  const huffmanSpecs = [];
  let frame = null;
  let restart = 0;
  let scan = null;
  while (offset + 4 < bytes.length) {
    if (bytes[offset] !== 0xff) return null;
    while (bytes[offset] === 0xff) offset += 1;
    const marker = bytes[offset++];
    if (marker === 0xd9) break;
    if (marker === 0xda) {
      const length = bytes.readUInt16BE(offset);
      const count = bytes[offset + 2];
      const components = [];
      let cursor = offset + 3;
      for (let i = 0; i < count; i++) {
        components.push({id: bytes[cursor], dc: bytes[cursor + 1] >> 4, ac: bytes[cursor + 1] & 15});
        cursor += 2;
      }
      scan = {components, spectralStart: bytes[cursor], spectralEnd: bytes[cursor + 1], approx: bytes[cursor + 2]};
      offset = offset + length;
      break;
    }
    if (marker === 0xd8 || (marker >= 0xd0 && marker <= 0xd7)) continue;
    const length = bytes.readUInt16BE(offset);
    const dataStart = offset + 2;
    const dataEnd = offset + length;
    if (dataEnd > bytes.length) return null;
    if (marker === 0xdb) {
      let cursor = dataStart;
      while (cursor < dataEnd) {
        const header = bytes[cursor++];
        const table = new Float64Array(64);
        const precision = header >> 4;
        if (precision !== 0) return null;
        for (let i = 0; i < 64; i++) table[i] = bytes[cursor++];
        quant[header & 15] = table;
      }
    } else if (marker === 0xc4) {
      let cursor = dataStart;
      while (cursor < dataEnd) {
        const header = bytes[cursor++];
        const counts = [...bytes.subarray(cursor, cursor + 16)];
        cursor += 16;
        const total = counts.reduce((sum, count) => sum + count, 0);
        const symbols = [...bytes.subarray(cursor, cursor + total)];
        cursor += total;
        huffmanSpecs.push({classId: header >> 4, id: header & 15, counts, symbols});
      }
    } else if (marker === 0xc0) {
      const height = bytes.readUInt16BE(dataStart + 1);
      const width = bytes.readUInt16BE(dataStart + 3);
      const count = bytes[dataStart + 5];
      const components = [];
      let cursor = dataStart + 6;
      for (let i = 0; i < count; i++) {
        components.push({id: bytes[cursor], h: bytes[cursor + 1] >> 4, v: bytes[cursor + 1] & 15, quant: bytes[cursor + 2]});
        cursor += 3;
      }
      frame = {width, height, components};
    } else if (marker === 0xc1 || marker === 0xc2 || (marker >= 0xc3 && marker <= 0xcf)) {
      return null;
    } else if (marker === 0xdd) {
      restart = bytes.readUInt16BE(dataStart);
    }
    offset = dataEnd;
  }
  if (!frame || !scan || frame.width < 1 || frame.height < 1 || frame.width > 4096 || frame.height > 4096) return null;
  if (scan.spectralStart !== 0 || scan.spectralEnd !== 63) return null;
  const trees = readHuffman(huffmanSpecs);
  const maxH = Math.max(...frame.components.map(component => component.h));
  const maxV = Math.max(...frame.components.map(component => component.v));
  const mcuWidth = maxH * 8;
  const mcuHeight = maxV * 8;
  const mcusX = Math.ceil(frame.width / mcuWidth);
  const mcusY = Math.ceil(frame.height / mcuHeight);
  const gray = new Float64Array(frame.width * frame.height);
  const reader = new BitReader(bytes, offset);
  const predictors = new Map(frame.components.map(component => [component.id, 0]));
  let mcuCount = 0;
  const scanComponents = scan.components.map(component => {
    const frameComponent = frame.components.find(item => item.id === component.id);
    return {...component, ...frameComponent};
  });
  for (let my = 0; my < mcusY; my++) {
    for (let mx = 0; mx < mcusX; mx++) {
      if (restart && mcuCount > 0 && mcuCount % restart === 0) {
        reader.bit = 0;
        if (reader.offset >= reader.bytes.length || reader.bytes[reader.offset] !== 0xff) return null;
        reader.offset += 1;
        while (reader.offset < reader.bytes.length && reader.bytes[reader.offset] === 0xff) reader.offset += 1;
        const marker = reader.bytes[reader.offset++] ?? 0;
        if (marker < 0xd0 || marker > 0xd7) return null;
        for (const key of predictors.keys()) predictors.set(key, 0);
      }
      mcuCount += 1;
      for (const component of scanComponents) {
        const blocks = [];
        for (let i = 0; i < component.h * component.v; i++) {
          const decoded = decodeBlock(reader, trees.dc[component.dc], trees.ac[component.ac], quant[component.quant], predictors.get(component.id) ?? 0);
          if (!decoded) return null;
          predictors.set(component.id, decoded.dc);
          blocks.push(decoded.samples);
        }
        if (component.id !== scanComponents[0].id) continue;
        const stepX = mcuWidth / component.h;
        const stepY = mcuHeight / component.v;
        for (let by = 0; by < component.v; by++) {
          for (let bx = 0; bx < component.h; bx++) {
            const block = blocks[by * component.h + bx];
            for (let y = 0; y < 8; y++) {
              for (let x = 0; x < 8; x++) {
                const sample = Math.min(255, Math.max(0, block[y * 8 + x] + 128));
                const originX = mx * mcuWidth + bx * stepX + Math.floor(x * stepX / 8);
                const originY = my * mcuHeight + by * stepY + Math.floor(y * stepY / 8);
                for (let py = 0; py < stepY / 8; py++) {
                  for (let px = 0; px < stepX / 8; px++) {
                    const pixelX = originX + px;
                    const pixelY = originY + py;
                    if (pixelX < frame.width && pixelY < frame.height) gray[pixelY * frame.width + pixelX] = sample / 255;
                  }
                }
              }
            }
          }
        }
      }
    }
  }
  return {width: frame.width, height: frame.height, gray};
}

export function decodeCartoonGray(bytes) {
  const buffer = Buffer.isBuffer(bytes) ? bytes : Buffer.from(bytes);
  if (buffer.length >= 8 && buffer[0] === 0x89) return decodePNG(buffer);
  if (buffer.length >= 3 && buffer[0] === 0xff && buffer[1] === 0xd8) return decodeJPEG(buffer);
  return null;
}

function resample(image, size) {
  const out = new Float64Array(size * size);
  for (let y = 0; y < size; y++) {
    const sy0 = Math.floor(y * image.height / size);
    const sy1 = Math.max(sy0 + 1, Math.floor((y + 1) * image.height / size));
    for (let x = 0; x < size; x++) {
      const sx0 = Math.floor(x * image.width / size);
      const sx1 = Math.max(sx0 + 1, Math.floor((x + 1) * image.width / size));
      let sum = 0;
      let count = 0;
      for (let sy = sy0; sy < sy1; sy++) {
        for (let sx = sx0; sx < sx1; sx++) {
          sum += image.gray[sy * image.width + sx];
          count += 1;
        }
      }
      out[y * size + x] = sum / count;
    }
  }
  return out;
}

function edgeMap(pixels, size) {
  const out = new Float64Array(size * size);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const index = y * size + x;
      const right = x + 1 < size ? Math.abs(pixels[index] - pixels[index + 1]) : 0;
      const down = y + 1 < size ? Math.abs(pixels[index] - pixels[index + size]) : 0;
      out[index] = right + down;
    }
  }
  return out;
}

function ssim(left, right) {
  const count = left.length;
  let meanLeft = 0;
  let meanRight = 0;
  for (let i = 0; i < count; i++) { meanLeft += left[i]; meanRight += right[i]; }
  meanLeft /= count;
  meanRight /= count;
  let varianceLeft = 0;
  let varianceRight = 0;
  let covariance = 0;
  for (let i = 0; i < count; i++) {
    const dl = left[i] - meanLeft;
    const dr = right[i] - meanRight;
    varianceLeft += dl * dl;
    varianceRight += dr * dr;
    covariance += dl * dr;
  }
  varianceLeft /= count;
  varianceRight /= count;
  covariance /= count;
  const c1 = 0.01 * 0.01;
  const c2 = 0.03 * 0.03;
  return ((2 * meanLeft * meanRight + c1) * (2 * covariance + c2)) / ((meanLeft * meanLeft + meanRight * meanRight + c1) * (varianceLeft + varianceRight + c2));
}

// Line-layer check from acceptance.md: structure stays close (SSIM ≥ 0.5) and the
// output does not grow edges in regions the cropped drawing left blank.
export function cartoonStructureAccepts(sourceBytes, outputBytes) {
  const source = decodeCartoonGray(sourceBytes);
  const output = decodeCartoonGray(outputBytes);
  if (!source || !output) return false;
  const size = 32;
  const sourcePixels = resample(source, size);
  const outputPixels = resample(output, size);
  const sourceEdges = edgeMap(sourcePixels, size);
  const outputEdges = edgeMap(outputPixels, size);
  const structure = ssim(sourceEdges, outputEdges);
  let added = 0;
  let total = 0;
  let present = 0;
  for (let i = 0; i < outputEdges.length; i++) {
    total += outputEdges[i];
    present += sourceEdges[i];
    if (sourceEdges[i] < 0.08 && outputEdges[i] > 0.22) added += outputEdges[i];
  }
  if (total < 0.05) return ssim(sourcePixels, outputPixels) >= 0.5;
  // Compare new edge energy with the drawing that was already there, so one extra
  // object fails even when the original lines dominate the output.
  const addedRatio = present < 0.05 ? added / total : added / present;
  return structure >= 0.5 && addedRatio < 0.2;
}
