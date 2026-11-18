// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
const m=require('./stan-motion-core.js');
const out={};
const times=[0,0.4,1,1.5,2,2.7,3,3.6,4.2,5,5.5,5.9,6.1,6.5,9.1,11.5,13.3];
for(const k of Object.keys(m.SCENE)){out[k]={};for(const t of times){out[k][t]=m.SCENE[k](t);}}
process.stdout.write(JSON.stringify(out));
