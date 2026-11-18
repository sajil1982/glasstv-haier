"""Layered paper-cut scenery, 4 light states x 5 weather states (1920x1080), drawn from code."""
import numpy as np, sys, os
from PIL import Image, ImageFilter, ImageDraw
W,H=1920,1080
def hexc(h): h=h.lstrip('#'); return np.array([int(h[i:i+2],16) for i in (0,2,4)],np.float32)
def lerp(a,b,t): return a+(b-a)*t
def noise1(n,scale,seed,octaves=4,persist=0.5):
    r=np.random.default_rng(seed); out=np.zeros(n); amp=1; tot=0
    for o in range(octaves):
        k=max(2,int(n/scale*2**o)); pts=r.random(k+3)
        x=np.linspace(0,k,n); i=np.floor(x).astype(int); f=x-i; f=f*f*(3-2*f)
        out+=(pts[i]*(1-f)+pts[i+1]*f)*amp; tot+=amp; amp*=persist
    return out/tot
PAL={ # sky rings (outer->inner), sun, 5 land layers far->near, cloud
 'dawn': dict(sky=['#3d3a6b','#5a4a82','#8a5f93','#c9799a','#f2a48f','#fbd0a0'],sun='#fff1cf',sx=.68,sy=.60,
              land=['#b98aa8','#9a6f9a','#75528a','#4c3a6e','#2a2548'],cloud='#fbe3d6'),
 'day':  dict(sky=['#2f6fb5','#4388c8','#5aa0d6','#7bb8e2','#a3d0ec','#cfe6f4'],sun='#fff6c8',sx=.70,sy=.22,
              land=['#8fc4c2','#63a8a0','#3f8a7c','#2a6a5c','#17463f'],cloud='#ffffff'),
 'dusk': dict(sky=['#2a1f52','#4a2a6a','#7d3572','#b9486a','#ea6e55','#f9a65a'],sun='#ffe0a0',sx=.30,sy=.62,
              land=['#b0567a','#8a3f74','#632f68','#3f2354','#22163a'],cloud='#f6b79a'),
 'night':dict(sky=['#070b22','#0c1333','#121c45','#1a2858','#24366c','#31477f'],sun='#f4f1dc',sx=.74,sy=.22,
              land=['#2b3f72','#22325e','#19264b','#111a38','#0a1026'],cloud='#3d4f85'),
}
WX={'clear':dict(clouds=2,grey=0,dark=0),'cloudy':dict(clouds=8,grey=.25,dark=.05),'fog':dict(clouds=3,grey=.35,dark=0),
    'rain':dict(clouds=10,grey=.6,dark=.18),'storm':dict(clouds=11,grey=.75,dark=.32)}
GRAIN=np.random.default_rng(1).normal(0,1,(H,W)).astype(np.float32)
FIB=np.asarray(Image.fromarray((np.random.default_rng(2).random((H//6,W//2))*255).astype(np.uint8)).resize((W,H),Image.BICUBIC)).astype(np.float32)/255-0.5
def paste(img,mask,col,shadow=0.55,off=(0,-9),blur=16):
    """mask: float HxW 0..1. Drops a soft shadow onto what is behind, then lays the paper sheet."""
    m=Image.fromarray((mask*255).astype(np.uint8))
    sh=np.asarray(m.filter(ImageFilter.GaussianBlur(blur)).transform((W,H),Image.AFFINE,(1,0,-off[0],0,1,-off[1]))).astype(np.float32)/255
    img*= (1-shadow*sh*(1-mask))[...,None]
    edge=np.asarray(m.filter(ImageFilter.GaussianBlur(0.7))).astype(np.float32)/255
    sheet=col[None,None,:]*(1+0.035*FIB[...,None])+GRAIN[...,None]*1.1
    # lit top edge of the sheet
    hi=np.clip(np.roll(edge,3,axis=0)*-1+edge,0,1)*0.10
    sheet=sheet*(1+hi[...,None])
    return lerp(img,sheet,edge[...,None])
def circle(cx,cy,r):
    yy,xx=np.mgrid[0:H,0:W]; return np.clip(r-np.hypot(xx-cx,yy-cy)+0.5,0,1).astype(np.float32)
def cloud(cx,cy,s,seed):
    im=Image.new('L',(W,H),0); d=ImageDraw.Draw(im); r=np.random.default_rng(seed)
    n=5; base=cy+s*0.35
    for i in range(n):
        x=cx+(i-(n-1)/2)*s*0.62; rad=s*(0.42+0.30*np.sin(np.pi*(i+0.5)/n)+0.08*r.random())
        d.ellipse([x-rad,base-rad*1.6,x+rad,base+rad*0.4],fill=255)
    d.rounded_rectangle([cx-s*1.9,base-s*0.28,cx+s*1.9,base+s*0.22],radius=int(s*0.25),fill=255)
    d.rectangle([0,int(base+s*0.22),W,H],fill=0)
    return np.asarray(im).astype(np.float32)/255
PROF={}
def snow(i):
    prof=PROF[i]; yy=np.arange(H)[:,None]; zig=18*np.abs(((np.arange(W)/46)%2)-1)
    line=prof.min()+ (70 if i==0 else 46)+zig
    return (np.clip(yy-prof[None,:]+0.5,0,1)*np.clip(line[None,:]-yy,0,1)).astype(np.float32)
def river():
    im=Image.new('L',(W,H),0); d=ImageDraw.Draw(im); L_=[];R_=[]
    for k in range(60):
        t=k/59; y=H*(0.715+0.31*t); cx=W*(0.53+0.085*np.sin(t*7.5+0.4)*(0.5+0.9*t)); w=9+190*t**1.5
        L_.append((cx-w,y)); R_.append((cx+w,y))
    d.polygon(L_+R_[::-1],fill=255); return np.asarray(im).astype(np.float32)/255
def frame(layer):
    im=Image.new('L',(W,H),0); d=ImageDraw.Draw(im); r=np.random.default_rng(200+layer); depth=[150,95,48][layer]
    for side in (0,1):
        for k in range(16):
            y=-80+k*(H+160)/15+r.normal(0,22); rad=95+r.random()*70
            top=max(0,1-y/(H*0.30)); bot=max(0,(y-H*0.80)/(H*0.2))
            x=depth*(0.55+0.9*top*(1.6 if layer==0 else 1.2)+0.25*bot)-rad*0.55+r.normal(0,16)
            cx=x if side==0 else W-x
            d.ellipse([cx-rad*1.25,y-rad,cx+rad*1.25,y+rad],fill=255)
        d.rectangle([0,0,depth*0.25,H] if side==0 else [W-depth*0.25,0,W,H],fill=255)
    for k in range(12):
        x=r.random()*W; edge=min(x,W-x)/(W/2); rad=80+r.random()*60
        if edge>0.55: continue
        y=-rad*0.35+depth*0.35*(1-edge/0.55)-40
        d.ellipse([x-rad*1.3,y-rad,x+rad*1.3,y+rad],fill=255)
    return np.asarray(im).astype(np.float32)/255
def roundtree(x,b,s):
    im=Image.new('L',(W,H),0); d=ImageDraw.Draw(im)
    d.polygon([(x-s*0.07,b),(x+s*0.07,b),(x+s*0.03,b-s*0.8),(x-s*0.03,b-s*0.8)],fill=255)
    for dx,dy,rr in ((0,-1.0,.38),(-.30,-.82,.30),(.30,-.84,.30),(0,-.70,.34)): d.ellipse([x+dx*s-rr*s,b+dy*s-rr*s*0.8,x+dx*s+rr*s,b+dy*s+rr*s*0.8],fill=255)
    return np.asarray(im).astype(np.float32)/255
def birds(seed):
    im=Image.new('L',(W,H),0); d=ImageDraw.Draw(im); r=np.random.default_rng(seed)
    for _ in range(7):
        x=W*(0.30+0.25*r.random()); y=H*(0.20+0.18*r.random()); s=14+r.random()*12
        d.polygon([(x-s*1.6,y-s*0.5),(x-s*0.5,y-s*0.15),(x,y+s*0.35),(x+s*0.5,y-s*0.15),(x+s*1.6,y-s*0.5),(x+s*0.5,y+s*0.12),(x,y+s*0.75),(x-s*0.5,y+s*0.12)],fill=255)
    return np.asarray(im).astype(np.float32)/255
def land(i,seed=0):
    base=[0.56,0.63,0.70,0.79,0.89][i]; amp=[0.16,0.13,0.10,0.07,0.04][i]; sc=[520,620,760,900,1100][i]
    h=noise1(W,sc,30+i+seed,4)
    if i<2: h=np.abs(noise1(W,sc*0.8,60+i,3)-0.5)*-2+1   # sharper peaks far away
    prof=(base-amp*(h-0.45)*2)*H
    yy=np.arange(H)[:,None]
    m=np.clip(yy-prof[None,:]+0.5,0,1).astype(np.float32)
    PROF[i]=prof
    if i>=3:   # paper pine trees along the two nearest sheets
        im=Image.new('L',(W,H),0); d=ImageDraw.Draw(im); r=np.random.default_rng(70+i)
        for k in range(26 if i==3 else 18):
            x=r.random()*W
            if i==4 and 0.22*W<x<0.80*W: continue
            b=prof[int(min(W-1,x))]+8; th=H*((0.07 if i==3 else 0.11)+r.random()*0.07); ww=th*0.30
            for j in range(3):
                y0=b-th*(j*0.28); y1=b-th*(j*0.28+0.48); wj=ww*(1-j*0.26)
                d.polygon([(x-wj,y0),(x+wj,y0),(x,y1)],fill=255)
        m=np.maximum(m,np.asarray(im).astype(np.float32)/255)
    return m
def render(light,wx):
    P=PAL[light]; X=WX[wx]; grey=hexc('#8a8f99') if light!='night' else hexc('#1a1f2e')
    def tone(c,k=1.0): 
        c=lerp(hexc(c) if isinstance(c,str) else c,grey,X['grey']*k); return c*(1-X['dark'])
    img=np.ones((H,W,3),np.float32)*tone(P['sky'][0])[None,None,:]+GRAIN[...,None]*1.1
    sx,sy=P['sx']*W,P['sy']*H
    for k,c in enumerate(P['sky'][1:]):
        r=[1150,900,680,480,300][k]
        img=paste(img,circle(sx,sy,r),tone(c),shadow=0.22,off=(0,0),blur=22)
    if light=='night' and wx in('clear','cloudy','fog'):
        r=np.random.default_rng(5); im=Image.new('L',(W,H),0); d=ImageDraw.Draw(im)
        for _ in range(70):
            x,y=r.random()*W,r.random()*H*0.55; s=2+r.random()*3.5; d.ellipse([x-s,y-s,x+s,y+s],fill=255)
        img=paste(img,np.asarray(im).astype(np.float32)/255,tone('#f1ecd2',.3),shadow=0.3,off=(0,3),blur=4)
    if wx in('clear','cloudy','fog'):
        disc=circle(sx,sy,78)
        if light=='night': disc=np.clip(disc-circle(sx+34,sy-18,70),0,1)
        img=paste(img,disc,tone(P['sun'],.4),shadow=0.35,off=(0,6),blur=12)
    r=np.random.default_rng({'clear':3,'cloudy':4,'fog':5,'rain':6,'storm':7}[wx])
    ccol=tone(P['cloud'],1.1 if wx in('rain','storm') else .5)
    if wx in('rain','storm'): ccol=ccol*0.78
    cl=[(r.random()*W,H*(0.08+0.30*r.random()),70+r.random()*70) for _ in range(X['clouds'])]
    for j,(cx,cy,s) in enumerate(sorted(cl,key=lambda t:t[2])[:len(cl)//2]):
        img=paste(img,cloud(cx,cy,s,j),ccol*0.90,shadow=0.30,off=(0,8),blur=12)
    L=[tone(c) for c in P['land']]
    for i in range(5):
        if wx=='fog' and i in(1,2,3,4):
            yy=np.arange(H)[:,None]; wave=(0.60+0.085*i+0.012*np.sin(np.arange(W)/170+i*2))*H
            band=np.clip((yy-wave[None,:])/3,0,1)*np.clip((wave[None,:]+110-yy)/3,0,1)
            fc=lerp(tone(P['cloud'],.3),L[i],0.12)
            img=lerp(img,paste(img.copy(),band.astype(np.float32),fc,shadow=0.18),0.92)
        img=paste(img,land(i),L[i])
        if i<2: img=paste(img,snow(i),tone('#ffffff' if light!='night' else '#b9c4e6',.25),shadow=0.25,off=(0,5),blur=6)
        if i==1 and light!='night' and wx in('clear','cloudy'): img=paste(img,birds(9),lerp(L[4],np.zeros(3,np.float32),0.2),shadow=0.3,off=(0,8),blur=6)
        if i==2:
            for (tx,ts) in ((0.27,210),(0.76,250),(0.34,150)):
                img=paste(img,roundtree(W*tx,PROF[2][int(W*tx)]+20,ts),lerp(L[3],L[4],0.4))
        if i==4: img=paste(img,river(),tone({'dawn':'#f0b9a6','day':'#8fd0e8','dusk':'#f08f6a','night':'#3e5aa0'}[light],.6),shadow=0.5,off=(0,7),blur=10)
    for j,(cx,cy,s) in enumerate(sorted(cl,key=lambda t:t[2])[len(cl)//2:]):
        img=paste(img,cloud(cx,cy,s*1.15,20+j),ccol,shadow=0.34,off=(0,10),blur=14)
    if wx=='storm':
        im=Image.new('L',(W,H),0); d=ImageDraw.Draw(im); x0=W*0.57; y0=330
        d.polygon([(x0,y0),(x0+64,y0),(x0+22,y0+150),(x0+76,y0+150),(x0-50,y0+400),(x0-8,y0+210),(x0-60,y0+210)],fill=255)
        img=paste(img,np.asarray(im).astype(np.float32)/255,hexc('#ffe98a'),shadow=0.45,off=(0,8),blur=14)
    if wx in('rain','storm'):
        im=Image.new('L',(W,H),0); d=ImageDraw.Draw(im); rr=np.random.default_rng(11)
        for _ in range(150 if wx=='rain' else 230):
            x,y=rr.random()*W,H*0.30+rr.random()*H*0.62; ln=26+rr.random()*22
            d.line([(x,y),(x-ln*0.28,y+ln)],fill=255,width=5)
        img=paste(img,np.asarray(im).astype(np.float32)/255,tone('#dfe8f2',.5),shadow=0.30,off=(3,5),blur=4)
    fr=[lerp(L[2],L[3],.5),L[3]*0.92,L[4]*0.9]
    for k in range(3): img=paste(img,frame(k),fr[k],shadow=0.6,off=(0,0),blur=22)
    # soft vignette, darker at the bottom for the dock
    yy=np.linspace(0,1,H)[:,None,None]; img*=1-0.22*np.clip((yy-0.72)/0.28,0,1)
    return Image.fromarray(np.clip(img,0,255).astype(np.uint8))
if __name__=='__main__':
    out=sys.argv[1]; os.makedirs(out,exist_ok=True); only=sys.argv[2:]
    for l in PAL:
        for w in WX:
            if only and f'{l}-{w}' not in only: continue
            render(l,w).save(f'{out}/{l}-{w}.jpg',quality=94,subsampling=0,optimize=True)
