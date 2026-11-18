"""Draws one Sahyadri lake scene in 4 light states x 5 weather states (1920x1080), all from code."""
import numpy as np, math, sys, os
from PIL import Image, ImageFilter, ImageDraw

W,H=1920,1080
rng0=np.random.default_rng(7)

def noise2(w,h,scale,seed,octaves=5,persist=0.5,sy=None):
    sy=sy or scale
    r=np.random.default_rng(seed); out=np.zeros((h,w),np.float32); amp=1; tot=0
    for o in range(octaves):
        gw=max(2,int(w/scale*2**o)); gh=max(2,int(h/sy*2**o))
        g=r.random((gh,gw)).astype(np.float32)
        img=Image.fromarray(g,'F').resize((w,h),Image.BICUBIC)
        out+=np.asarray(img)*amp; tot+=amp; amp*=persist
    return out/tot

def noise1(n,scale,seed,octaves=6,persist=0.5):
    r=np.random.default_rng(seed); out=np.zeros(n); amp=1; tot=0
    for o in range(octaves):
        k=max(2,int(n/scale*2**o)); pts=r.random(k+3)
        x=np.linspace(0,k,n); i=np.floor(x).astype(int); f=x-i; f=f*f*(3-2*f)
        out+=(pts[i]*(1-f)+pts[i+1]*f)*amp; tot+=amp; amp*=persist
    return out/tot

def hexc(h): h=h.lstrip('#'); return np.array([int(h[i:i+2],16) for i in (0,2,4)],np.float32)
def lerp(a,b,t): return a+(b-a)*t

# palettes per light: sky top, sky horizon, sun colour, sun pos (x,y), light tint for ridges, far ridge, near ridge, water tint
LIGHT={
 'dawn':  dict(top='#3b4a78',hor='#f2b48a',sun='#ffd9a0',sx=0.72,sy=0.50,far='#9a8aa6',near='#2a2638',cloudlit='#f6c3a0',cloudsh='#6a6488'),
 'day':   dict(top='#3f7fc7',hor='#bfdcf0',sun='#fffbe8',sx=0.70,sy=0.16,far='#8fb0c8',near='#23382f',cloudlit='#ffffff',cloudsh='#9fb3c8'),
 'dusk':  dict(top='#2b2a5a',hor='#ff9a5c',sun='#ffcf80',sx=0.30,sy=0.52,far='#8a6a80',near='#1f1a2a',cloudlit='#ffb58a',cloudsh='#5a4a6e'),
 'night': dict(top='#05081a',hor='#1c2a4a',sun='#e8eeff',sx=0.76,sy=0.18,far='#2a3654',near='#070a14',cloudlit='#4a5a7e',cloudsh='#121a30'),
}
# weather: cloud cover, darkness, haze, rain amount, desaturation
WX={
 'clear': dict(cover=0.18,dark=0.0,haze=0.15,rain=0,desat=0.0),
 'cloudy':dict(cover=0.62,dark=0.12,haze=0.25,rain=0,desat=0.18),
 'fog':   dict(cover=0.55,dark=0.05,haze=0.80,rain=0,desat=0.45),
 'rain':  dict(cover=0.92,dark=0.22,haze=0.50,rain=1,desat=0.80),
 'storm': dict(cover=1.00,dark=0.42,haze=0.45,rain=2,desat=0.85),
}
HORIZON=int(H*0.64)

def render(light,wx):
    L=LIGHT[light]; X=WX[wx]
    yy,xx=np.mgrid[0:H,0:W].astype(np.float32)
    top=hexc(L['top']); hor=hexc(L['hor'])
    grey=np.array([120,128,140],np.float32) if light!='night' else np.array([22,26,36],np.float32)
    if light in('dawn','dusk'): grey=np.array([110,104,118],np.float32)
    top=lerp(top,grey*0.7,X['desat']); hor=lerp(hor,grey*1.15,X['desat'])
    t=np.clip(yy/HORIZON,0,1)[...,None]**1.3
    img=lerp(top,hor,t)
    # sun / moon glow
    sx,sy=L['sx']*W,L['sy']*H; d=np.hypot(xx-sx,yy-sy)
    vis=max(0.0,1-X['cover']*1.05)
    sunc=hexc(L['sun'])
    glow=np.exp(-(d/(260 if light!='night' else 140))**2)*(0.55*vis+0.15)
    img=lerp(img,sunc,glow[...,None])
    if light=='night' and wx in('clear','cloudy'):
        r=np.random.default_rng(3); n=900
        for _ in range(n):
            x=r.integers(0,W); y=r.integers(0,int(HORIZON*0.9)); b=r.random()**3
            if wx=='cloudy' and r.random()<0.7: continue
            img[y,x]=lerp(img[y,x],np.array([255,255,255],np.float32),0.35+0.65*b)
    disc=d<(34 if light!='night' else 30)
    if vis>0.35: img[disc]=lerp(img[disc],sunc,min(1,vis*1.2))
    # clouds: flattened cumulus, lit from the sun's side
    n=noise2(W,H,300,13,6,sy=110)
    n=(n-n.min())/(n.max()-n.min())
    n2=n
    dx=int(np.sign(sx-W/2)*-18)
    pad=np.pad(n2,((14,0),(abs(dx),abs(dx))),mode='edge')
    shift=pad[:H, abs(dx)-dx:abs(dx)-dx+W]
    alt=np.clip(1-(yy/HORIZON),0,1)
    thr={'clear':0.72,'cloudy':0.48,'fog':0.66,'rain':0.40,'storm':0.34}[wx]
    c=np.clip((n-thr)/0.10,0,1)*np.clip((HORIZON*0.96-yy)/140,0,1)
    if wx=='clear': c*=np.clip((yy-H*0.18)/(H*0.2),0,1)*0.85
    if wx in('rain','storm'): c=np.maximum(c,(0.80+0.2*np.clip((n-0.3)/0.4,0,1))*np.clip((HORIZON*0.96-yy)/140,0,1))
    shade=np.clip((n-shift)*9+0.55,0,1)
    lit=hexc(L['cloudlit']); sh=hexc(L['cloudsh'])
    lit=lerp(lit,grey*1.25,X['desat']); sh=lerp(sh,grey*0.55,X['desat'])
    ccol=lerp(sh,lit,shade[...,None])
    ccol=lerp(ccol,hor,(np.clip(yy/HORIZON,0,1)**3*0.5)[...,None])
    if wx in('rain','storm'): ccol*=0.85
    img=lerp(img,ccol,(c*0.97)[...,None])
    if wx=='storm':
        # lightning lit cloud region + bolt
        lg=np.exp(-((xx-W*0.58)**2/(320**2)+(yy-H*0.18)**2/(200**2)))
        img=lerp(img,np.array([200,205,255],np.float32),(lg*0.45)[...,None])
    # ridges
    sky=img.copy()
    layers=[(0.47,0.13,140,0.88,0.62),(0.53,0.10,160,0.66,0.66),(0.585,0.07,180,0.42,0.72),(0.625,0.035,220,0.18,0.9)]
    far=hexc(L['far']); near=hexc(L['near'])
    far=lerp(far,grey,X['desat']*0.8); near=lerp(near,grey*0.25,X['desat']*0.5)
    hazecol=hor if light!='night' else hexc('#26324e')
    for i,(base,amp,scale,depth,plat) in enumerate(layers):
        h=noise1(W,scale*3,20+i,6)
        h=np.minimum(h,plat)          # flat basalt tops, as on the Sahyadri mesas
        h=h+0.015*noise1(W,25,60+i,3)
        prof=(base-amp*(h-0.3)*2.4)*H
        col=lerp(near,far,depth)
        hz=min(1,X['haze']*(depth+0.25)*1.4)
        col=lerp(col,hazecol,hz)
        # sun-facing rim light on the far layers
        mask=(yy>=prof[None,:])&(yy<HORIZON)
        shadef=np.clip((yy-prof[None,:])/220,0,1)*0.28
        lay=col[None,None,:]*(1-shadef[...,None])
        img=np.where(mask[...,None],lay,img)
        if X['haze']>0.2:
            fogb=np.exp(-((yy-(prof.min()+90))/55)**2)*X['haze']*0.6*noise2(W,H,600,40+i,3,sy=60)
            img=lerp(img,hazecol,np.clip(fogb,0,1)[...,None])
    # water: reflection
    wy=np.arange(HORIZON,H)
    src=np.clip(2*HORIZON-wy,0,HORIZON-1)
    rip=(noise2(W,H-HORIZON,90,77,3,sy=4)-0.5)*22
    xs=np.clip((np.arange(W)[None,:]+rip).astype(int),0,W-1)
    refl=img[src[:,None],xs]
    watercol=lerp(hexc('#0d1a26'),grey*0.35,X['desat'])
    if light=='night': watercol=hexc('#03060d')
    depthw=((wy-HORIZON)/(H-HORIZON))[:,None,None]
    wat=lerp(refl*0.70,watercol,np.clip(depthw*0.9+0.10,0,1))
    # sun glitter path
    if vis>0.3:
        gl=np.exp(-((np.arange(W)-sx)/(70+260*depthw[...,0]))**2)*np.clip((noise2(W,H-HORIZON,60,5,3,sy=3)-0.55)/0.12,0,1)*vis
        wat=lerp(wat,sunc,np.clip(gl,0,1)[...,None]*0.6)
    img[HORIZON:]=wat
    # foreground shore + trees, keeps the bottom dark for the dock
    shore=(0.915-0.03*noise1(W,600,90,5))*H
    fg=hexc('#05070a') if light=='night' else lerp(near*0.35,np.zeros(3),0.4)
    img=np.where((yy>=shore[None,:])[...,None],fg,img)
    pil=Image.fromarray(np.clip(img,0,255).astype(np.uint8))
    dr=ImageDraw.Draw(pil)
    r=np.random.default_rng(55)
    fgc=tuple(int(v) for v in fg)
    for side in (0,1):
        for k in range(14):
            x=(r.random()*0.16 if side==0 else 0.86+r.random()*0.14)*W
            base=shore[int(min(W-1,x))]+6
            th=H*(0.08+r.random()*0.14)
            wdt=th*0.22
            for j in range(9):
                y0=base-th*(j/9); y1=base-th*((j+1.6)/9); ww=wdt*(1-j/9)
                dr.polygon([(x-ww,y0),(x+ww,y0),(x,y1)],fill=fgc)
            dr.line([(x,base),(x,base-th*0.15)],fill=fgc,width=4)
    img=np.asarray(pil).astype(np.float32)
    if wx=='fog':
        mist=np.clip((yy-H*0.40)/(H*0.25),0,1)*np.clip((H*0.92-yy)/(H*0.2),0,1)
        mist=mist*(0.55+0.45*noise2(W,H,700,88,3,sy=90))
        mc=hexc(L['hor']) if light!='night' else hexc('#2a3552')
        mc=lerp(mc,np.array([200,205,210],np.float32) if light!='night' else mc,0.5)
        img=lerp(img,mc,np.clip(mist*0.75,0,1)[...,None])
    # darkness
    img*=1-X['dark']
    # rain
    if X['rain']:
        rl=Image.new('L',(W,H),0); d2=ImageDraw.Draw(rl); r=np.random.default_rng(9)
        cnt=2600 if X['rain']==1 else 4200
        for _ in range(cnt):
            x=r.random()*W*1.2-W*0.1; y=r.random()*H; ln=26+r.random()*40
            d2.line([(x,y),(x-ln*0.22,y+ln)],fill=int(70+r.random()*90),width=1)
        rl=np.asarray(rl.filter(ImageFilter.GaussianBlur(0.6))).astype(np.float32)/255
        img=lerp(img,np.array([200,210,225],np.float32),(rl*0.35)[...,None])
    if wx=='storm':
        bolt=Image.new('L',(W,H),0); db=ImageDraw.Draw(bolt); r=np.random.default_rng(4)
        x,y=W*0.60,H*0.12; pts=[(x,y)]
        while y<H*0.52:
            x+=r.normal(0,22); y+=r.uniform(24,46); pts.append((x,y))
        db.line(pts,fill=255,width=4)
        g=np.asarray(bolt.filter(ImageFilter.GaussianBlur(10))).astype(np.float32)/255
        b=np.asarray(bolt.filter(ImageFilter.GaussianBlur(1))).astype(np.float32)/255
        img=lerp(img,np.array([220,225,255],np.float32),np.clip(g*0.8+b,0,1)[...,None])
    # grain
    img+=np.random.default_rng(1).normal(0,1.4,img.shape)
    return Image.fromarray(np.clip(img,0,255).astype(np.uint8))

if __name__=='__main__':
    out=sys.argv[1]; os.makedirs(out,exist_ok=True)
    only=sys.argv[2:] 
    for l in LIGHT:
        for w in WX:
            if only and f'{l}-{w}' not in only: continue
            render(l,w).save(f'{out}/{l}-{w}.jpg',quality=82,optimize=True,progressive=True); print(l,w,flush=True)
