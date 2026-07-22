// Minimal, self-contained QR encoder — byte mode, ECC L/M, versions 1-10.
// No dependencies (goes inside the app, which can't load external libs).
// Returns a 2D array of 0/1 modules. Verified against segno.
var QR = (function () {
  // ---- Galois field GF(256), primitive poly 0x11d ----
  var EXP = new Array(512), LOG = new Array(256);
  (function () {
    var x = 1;
    for (var i = 0; i < 255; i++) { EXP[i] = x; LOG[x] = i; x <<= 1; if (x & 0x100) x ^= 0x11d; }
    for (var i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
  })();
  function gmul(a, b) { if (a === 0 || b === 0) return 0; return EXP[LOG[a] + LOG[b]]; }

  // ---- Reed-Solomon ----
  function rsGenPoly(n) {
    // descending coefficients (length n+1), leading term first (gen[0] = 1)
    var poly = [1];
    for (var i = 0; i < n; i++) {
      var np = new Array(poly.length + 1).fill(0);
      for (var j = 0; j < poly.length; j++) {
        np[j] ^= poly[j];                      // x * poly
        np[j + 1] ^= gmul(poly[j], EXP[i]);     // α^i * poly
      }
      poly = np;
    }
    return poly;
  }
  function rsEncode(data, ecLen) {
    var gen = rsGenPoly(ecLen);                 // descending, length ecLen+1
    var msg = data.slice().concat(new Array(ecLen).fill(0));
    for (var i = 0; i < data.length; i++) {
      var coef = msg[i];
      if (coef !== 0) for (var j = 0; j < gen.length; j++) msg[i + j] ^= gmul(gen[j], coef);
    }
    return msg.slice(data.length);
  }

  // ---- EC block tables: [ecPerBlock, [g1blocks,g1data],[g2blocks,g2data]] for L,M ----
  // index by version (1..10); each has {L:..., M:...}
  var EC = {
    1:  {L:[7,[1,19]],           M:[10,[1,16]]},
    2:  {L:[10,[1,34]],          M:[16,[1,28]]},
    3:  {L:[15,[1,55]],          M:[26,[1,44]]},
    4:  {L:[20,[1,80]],          M:[18,[2,32]]},
    5:  {L:[26,[1,108]],         M:[24,[2,43]]},
    6:  {L:[18,[2,68]],          M:[16,[4,27]]},
    7:  {L:[20,[2,78]],          M:[18,[4,31]]},
    8:  {L:[24,[2,97]],          M:[22,[2,38],[2,39]]},
    9:  {L:[30,[2,116]],         M:[22,[3,36],[2,37]]},
    10: {L:[18,[2,68],[2,69]],   M:[26,[4,43],[1,44]]}
  };
  var ALIGN = {1:[],2:[6,18],3:[6,22],4:[6,26],5:[6,30],6:[6,34],
               7:[6,22,38],8:[6,24,42],9:[6,26,46],10:[6,28,50]};

  function blocksFor(ver, ecl) {
    var e = EC[ver][ecl];
    var ecPer = e[0], groups = e.slice(1), blocks = [];
    for (var g = 0; g < groups.length; g++)
      for (var b = 0; b < groups[g][0]; b++) blocks.push(groups[g][1]);
    return {ecPer: ecPer, dataCounts: blocks};
  }
  function dataCapacity(ver, ecl) { // total data codewords
    var e = EC[ver][ecl], groups = e.slice(1), t = 0;
    for (var g = 0; g < groups.length; g++) t += groups[g][0] * groups[g][1];
    return t;
  }

  // ---- bit buffer ----
  function BitBuf() { this.bits = []; }
  BitBuf.prototype.put = function (val, len) {
    for (var i = len - 1; i >= 0; i--) this.bits.push((val >>> i) & 1);
  };

  function chooseVersion(len, ecl) {
    for (var v = 1; v <= 10; v++) {
      var ccBits = v < 10 ? 8 : 16;
      var need = 4 + ccBits + len * 8;              // mode + count + data (bits)
      if (need <= dataCapacity(v, ecl) * 8) return v;
    }
    throw new Error('data too long for v1-10');
  }

  function encodeData(bytes, ver, ecl) {
    var bb = new BitBuf();
    bb.put(0b0100, 4);                              // byte mode
    bb.put(bytes.length, ver < 10 ? 8 : 16);        // char count
    for (var i = 0; i < bytes.length; i++) bb.put(bytes[i], 8);
    var cap = dataCapacity(ver, ecl) * 8;
    // terminator
    for (var t = 0; t < 4 && bb.bits.length < cap; t++) bb.bits.push(0);
    while (bb.bits.length % 8 !== 0) bb.bits.push(0); // byte align
    // pad bytes
    var pads = [0xEC, 0x11], pi = 0;
    while (bb.bits.length < cap) { bb.put(pads[pi], 8); pi ^= 1; }
    // to codewords
    var cw = [];
    for (var b = 0; b < bb.bits.length; b += 8) {
      var v = 0; for (var k = 0; k < 8; k++) v = (v << 1) | bb.bits[b + k];
      cw.push(v);
    }
    // split into blocks, compute EC, interleave
    var bl = blocksFor(ver, ecl), counts = bl.dataCounts, ecPer = bl.ecPer;
    var dataBlocks = [], ecBlocks = [], off = 0;
    for (var i = 0; i < counts.length; i++) {
      var d = cw.slice(off, off + counts[i]); off += counts[i];
      dataBlocks.push(d);
      ecBlocks.push(rsEncode(d, ecPer));
    }
    var result = [];
    var maxData = Math.max.apply(null, counts);
    for (var c = 0; c < maxData; c++)
      for (var i = 0; i < dataBlocks.length; i++)
        if (c < dataBlocks[i].length) result.push(dataBlocks[i][c]);
    for (var c = 0; c < ecPer; c++)
      for (var i = 0; i < ecBlocks.length; i++) result.push(ecBlocks[i][c]);
    return result; // final codeword stream
  }

  // ---- matrix ----
  function makeMatrix(ver) {
    var size = ver * 4 + 17;
    var m = [], reserved = [];
    for (var r = 0; r < size; r++) { m.push(new Array(size).fill(null)); reserved.push(new Array(size).fill(false)); }
    function setF(r, c, v) { m[r][c] = v; reserved[r][c] = true; }
    // finder + separators
    function finder(r0, c0) {
      for (var r = -1; r <= 7; r++) for (var c = -1; c <= 7; c++) {
        var rr = r0 + r, cc = c0 + c;
        if (rr < 0 || cc < 0 || rr >= size || cc >= size) continue;
        var inRing = (r >= 0 && r <= 6 && (c === 0 || c === 6)) || (c >= 0 && c <= 6 && (r === 0 || r === 6));
        var inCore = (r >= 2 && r <= 4 && c >= 2 && c <= 4);
        setF(rr, cc, (inRing || inCore) ? 1 : 0);
      }
    }
    finder(0, 0); finder(0, size - 7); finder(size - 7, 0);
    // timing
    for (var i = 8; i < size - 8; i++) { setF(6, i, i % 2 === 0 ? 1 : 0); setF(i, 6, i % 2 === 0 ? 1 : 0); }
    // alignment — every pair of positions EXCEPT the three finder corners
    // (first,first),(first,last),(last,first). Patterns on the timing row/col
    // (e.g. (6,22)) are valid and DO get placed, overwriting timing there.
    var ap = ALIGN[ver], last = ap.length - 1;
    for (var a = 0; a < ap.length; a++) for (var b = 0; b < ap.length; b++) {
      if ((a === 0 && b === 0) || (a === 0 && b === last) || (a === last && b === 0)) continue;
      var r0 = ap[a], c0 = ap[b];
      for (var r = -2; r <= 2; r++) for (var c = -2; c <= 2; c++) {
        var ring = Math.max(Math.abs(r), Math.abs(c));
        setF(r0 + r, c0 + c, (ring === 2 || ring === 0) ? 1 : 0);
      }
    }
    // dark module
    setF(size - 8, 8, 1);
    // reserve format info areas
    for (var i = 0; i < 9; i++) { if (!reserved[8][i]) reserved[8][i] = true; if (!reserved[i][8]) reserved[i][8] = true; }
    for (var i = 0; i < 8; i++) { reserved[8][size - 1 - i] = true; reserved[size - 1 - i][8] = true; }
    // reserve version info (v7+)
    if (ver >= 7) {
      for (var i = 0; i < 6; i++) for (var j = 0; j < 3; j++) {
        reserved[i][size - 11 + j] = true; reserved[size - 11 + j][i] = true;
      }
    }
    return {m: m, reserved: reserved, size: size};
  }

  function placeData(mat, codewords) {
    var m = mat.m, reserved = mat.reserved, size = mat.size;
    var bits = [];
    for (var i = 0; i < codewords.length; i++) for (var b = 7; b >= 0; b--) bits.push((codewords[i] >> b) & 1);
    var idx = 0, up = true;
    for (var col = size - 1; col > 0; col -= 2) {
      if (col === 6) col--; // skip timing column
      for (var t = 0; t < size; t++) {
        var row = up ? size - 1 - t : t;
        for (var c = 0; c < 2; c++) {
          var cc = col - c;
          if (reserved[row][cc]) continue;
          m[row][cc] = idx < bits.length ? bits[idx] : 0;
          idx++;
        }
      }
      up = !up;
    }
    return mat;
  }

  function maskFn(k) {
    return [
      function (r, c) { return (r + c) % 2 === 0; },
      function (r, c) { return r % 2 === 0; },
      function (r, c) { return c % 3 === 0; },
      function (r, c) { return (r + c) % 3 === 0; },
      function (r, c) { return (Math.floor(r / 2) + Math.floor(c / 3)) % 2 === 0; },
      function (r, c) { return ((r * c) % 2) + ((r * c) % 3) === 0; },
      function (r, c) { return (((r * c) % 2) + ((r * c) % 3)) % 2 === 0; },
      function (r, c) { return (((r + c) % 2) + ((r * c) % 3)) % 2 === 0; }
    ][k];
  }

  function applyMask(mat, k) {
    var size = mat.size, out = [], fn = maskFn(k);
    for (var r = 0; r < size; r++) { out.push(mat.m[r].slice()); }
    for (var r = 0; r < size; r++) for (var c = 0; c < size; c++)
      if (!mat.reserved[r][c] && fn(r, c)) out[r][c] ^= 1;
    return out;
  }

  // format info: 15 bits BCH, XOR 0x5412
  function formatBits(ecl, mask) {
    var eclBits = {L: 1, M: 0, Q: 3, H: 2}[ecl];
    var data = (eclBits << 3) | mask;
    var rem = data << 10;
    var g = 0x537;
    for (var i = 14; i >= 10; i--) if ((rem >> i) & 1) rem ^= g << (i - 10);
    var bits = ((data << 10) | rem) ^ 0x5412;
    return bits & 0x7fff;
  }
  function placeFormat(grid, mat, ecl, mask) {
    var size = mat.size, fb = formatBits(ecl, mask), bit = function (i) { return (fb >> (14 - i)) & 1; };
    // around top-left
    for (var i = 0; i <= 5; i++) grid[8][i] = bit(i);
    grid[8][7] = bit(6); grid[8][8] = bit(7); grid[7][8] = bit(8);
    for (var i = 9; i <= 14; i++) grid[14 - i][8] = bit(i);
    // copy 2 vertical (bits 0-6): column 8, rows size-1 .. size-7 (dark module at size-8 untouched)
    for (var i = 0; i <= 6; i++) grid[size - 1 - i][8] = bit(i);
    // copy 2 horizontal (bits 7-14): row 8, cols size-8 .. size-1
    for (var i = 7; i <= 14; i++) grid[8][size - 8 + (i - 7)] = bit(i);
  }
  function versionBits(ver) {
    var rem = ver << 12, g = 0x1f25;
    for (var i = 17; i >= 12; i--) if ((rem >> i) & 1) rem ^= g << (i - 12);
    return (ver << 12) | rem;
  }
  function placeVersion(grid, mat, ver) {
    if (ver < 7) return;
    var size = mat.size, vb = versionBits(ver);
    for (var i = 0; i < 18; i++) {
      var b = (vb >> i) & 1, r = Math.floor(i / 3), c = i % 3;
      grid[r][size - 11 + c] = b; grid[size - 11 + c][r] = b;
    }
  }

  function penalty(grid) {
    var n = grid.length, p = 0;
    // rule 1: runs of 5+
    for (var pass = 0; pass < 2; pass++)
      for (var r = 0; r < n; r++) {
        var run = 1, prev = -1;
        for (var c = 0; c < n; c++) {
          var v = pass === 0 ? grid[r][c] : grid[c][r];
          if (v === prev) { run++; if (run === 5) p += 3; else if (run > 5) p += 1; }
          else { run = 1; prev = v; }
        }
      }
    // rule 2: 2x2 blocks
    for (var r = 0; r < n - 1; r++) for (var c = 0; c < n - 1; c++) {
      var v = grid[r][c];
      if (v === grid[r][c + 1] && v === grid[r + 1][c] && v === grid[r + 1][c + 1]) p += 3;
    }
    // rule 3: finder-like pattern 1011101 with 4 light
    var pat1 = [1,0,1,1,1,0,1,0,0,0,0], pat2 = [0,0,0,0,1,0,1,1,1,0,1];
    for (var r = 0; r < n; r++) for (var c = 0; c <= n - 11; c++) {
      var okH1 = true, okH2 = true, okV1 = true, okV2 = true;
      for (var k = 0; k < 11; k++) {
        if (grid[r][c + k] !== pat1[k]) okH1 = false;
        if (grid[r][c + k] !== pat2[k]) okH2 = false;
        if (grid[c + k][r] !== pat1[k]) okV1 = false;
        if (grid[c + k][r] !== pat2[k]) okV2 = false;
      }
      if (okH1) p += 40; if (okH2) p += 40; if (okV1) p += 40; if (okV2) p += 40;
    }
    // rule 4: dark proportion
    var dark = 0; for (var r = 0; r < n; r++) for (var c = 0; c < n; c++) dark += grid[r][c];
    var pct = dark * 100 / (n * n);
    var k5 = Math.floor(Math.abs(pct - 50) / 5);
    p += k5 * 10;
    return p;
  }

  function bytesOf(str) {
    // UTF-8 bytes (Monero addr is ASCII anyway)
    var out = [];
    for (var i = 0; i < str.length; i++) {
      var c = str.charCodeAt(i);
      if (c < 128) out.push(c);
      else if (c < 2048) { out.push(192 | (c >> 6)); out.push(128 | (c & 63)); }
      else { out.push(224 | (c >> 12)); out.push(128 | ((c >> 6) & 63)); out.push(128 | (c & 63)); }
    }
    return out;
  }

  // main: returns {size, modules[][], version, mask}
  function encode(str, ecl, forceVer, forceMask) {
    ecl = ecl || 'M';
    var bytes = bytesOf(str);
    var ver = forceVer || chooseVersion(bytes.length, ecl);
    var codewords = encodeData(bytes, ver, ecl);
    var base = makeMatrix(ver);
    placeData(base, codewords);
    var best = null, bestPen = Infinity, bestMask = 0;
    var masks = forceMask != null ? [forceMask] : [0,1,2,3,4,5,6,7];
    for (var mi = 0; mi < masks.length; mi++) {
      var k = masks[mi];
      var grid = applyMask(base, k);
      placeFormat(grid, base, ecl, k);
      placeVersion(grid, base, ver);
      var pen = penalty(grid);
      if (pen < bestPen) { bestPen = pen; best = grid; bestMask = k; }
    }
    return {size: base.size, modules: best, version: ver, mask: bestMask, ecl: ecl};
  }

  return {encode: encode};
})();

