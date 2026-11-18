// QuickJS has no browser TextDecoder. The tile reader calls decode only on individual UTF-8 protobuf strings.
globalThis.TextDecoder=class {
  decode(bytes) {
    const units=[];
    for (let i=0;i<bytes.length;i++) {
      const a=bytes[i];
      if(a<128)units.push(a);
      else if(a>=194 && a<224 && i+1<bytes.length)units.push(((a&31)<<6)|(bytes[++i]&63));
      else if(a>=224 && a<240 && i+2<bytes.length)units.push(((a&15)<<12)|((bytes[++i]&63)<<6)|(bytes[++i]&63));
      else if(a>=240 && a<245 && i+3<bytes.length)units.push(((a&7)<<18)|((bytes[++i]&63)<<12)|((bytes[++i]&63)<<6)|(bytes[++i]&63));
      else units.push(65533);
    }
    return units.map(n=>String.fromCodePoint(n<=0x10ffff?n:65533)).join('');
  }
};
