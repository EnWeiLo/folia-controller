"""Simplified -> Taiwan Traditional (phrases + variants) with OpenCC's dictionary files."""
import os
D=os.environ.get('OPENCC_DICT') or os.path.join(os.path.dirname(__file__),'occ-src/data/dictionary')
def load(name):
    m={}
    for line in open(os.path.join(D,name),encoding='utf-8'):
        if '\t' not in line: continue
        k,v=line.rstrip('\n').split('\t',1); m[k]=v.split(' ')[0]
    return m
def mm(text,d,maxlen):
    out=[];i=0
    while i<len(text):
        for L in range(min(maxlen,len(text)-i),0,-1):
            s=text[i:i+L]
            if s in d: out.append(d[s]); i+=L; break
        else: out.append(text[i]); i+=1
    return ''.join(out)
ST={**load('STCharacters.txt'),**load('STPhrases.txt')}
TWP=load('TWPhrases.txt') if os.path.exists(os.path.join(D,'TWPhrases.txt')) else {}
for f in os.listdir(D):
    if f.startswith('TWPhrases') and f.endswith('.txt') and f!='TWPhrases.txt' and 'Rev' not in f: TWP.update(load(f))
TWV=load('TWVariants.txt')
ml=lambda d:max(map(len,d)) if d else 1
a,b,c=ml(ST),ml(TWP),ml(TWV)
def conv(t): return mm(mm(mm(t,ST,a),TWP,b),TWV,c)
if __name__=='__main__':
    print(conv('后处理 镜头运动强度 纹理分辨率 字体 背景 默认 视频 自定义 质量 字幕继承歌词字体 透明度 软件'))
