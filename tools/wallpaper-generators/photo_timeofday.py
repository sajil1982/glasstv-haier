"""Time-of-day versions of one sunset photo: dawn, day, dusk (the original), night."""
import numpy as np, sys, os
from PIL import Image, ImageFilter
SRC=sys.argv[1]; OUT=sys.argv[2]; os.makedirs(OUT,exist_ok=True)
im=Image.open(SRC).convert('RGB').resize((1920,1080),Image.LANCZOS)
a=np.asarray(im).astype(np.float32)/255
H,W,_=a.shape
lum=(0.2126*a[...,0]+0.7152*a[...,1]+0.0722*a[...,2])[...,None]
yy=np.linspace(0,1,H)[:,None,None]
# where the bright sun sits, for glow work
sun=np.clip((lum[...,0]-0.80)/0.2,0,1)
sunsoft=np.asarray(Image.fromarray((sun*255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(60))).astype(np.float32)/255
dark=np.clip((0.30-lum)/0.20,0,1)          # grass silhouettes
def mix(x,y,t): return x+(y-x)*t
def save(arr,name):
    Image.fromarray((np.clip(arr,0,1)*255+0.5).astype(np.uint8)).save(f'{OUT}/{name}-clear.jpg',quality=94,subsampling=0,optimize=True)
# dusk: the photo as it is
save(a,'dusk')
# dawn: softer and paler, peach and lavender, lifted shadows
d=mix(a,lum*np.array([1.0,0.92,1.0]),0.25)
d=d*np.array([1.06,1.00,0.98])+0.06*(1-d)
d=mix(d,np.array([1.0,0.82,0.70]),0.10*np.clip(1-yy*1.4,0,1))
d=mix(d,np.array([1.0,0.95,0.85]),sunsoft[...,None]*0.25)
save(d,'dawn')
# day: blue sky and water from the pink and purple, a pale high-noon sun, green-tinged grass
r,g,b=a[...,0],a[...,1],a[...,2]
pinkness=np.clip((r+b)/2-g,0,1)[...,None]          # pink/purple areas: sky and water
sky_top=np.array([0.22,0.47,0.84]); sky_low=np.array([0.62,0.80,0.96])
skyc=mix(sky_top,sky_low,np.clip(yy/0.42,0,1))
water=mix(np.array([0.30,0.55,0.80]),np.array([0.12,0.30,0.48]),np.clip((yy-0.42)/0.58,0,1))
base=np.where(yy<0.415,skyc,water)
# keep the photo's cloud and ripple detail as light and shade on the new colours
detail=(lum-np.asarray(Image.fromarray((lum[...,0]*255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(25))).astype(np.float32)[...,None]/255)
cl=np.clip((lum-0.45)*1.6,0,1)
dayc=base*(0.85+0.35*cl)+detail*1.2
dayc=mix(dayc,np.array([1,1,1]),np.clip(cl*0.35*(yy<0.42),0,1))
grass=np.array([0.06,0.14,0.09])+lum*np.array([0.10,0.22,0.12])
day=mix(dayc,grass,np.clip(dark*1.4,0,1))
hills=np.clip(1-np.abs(yy-0.40)/0.035,0,1)*np.clip((0.30-lum)/0.3,0,1)
day=mix(day,np.array([0.30,0.42,0.48]),hills*0.8)
day=mix(day,np.array([1.0,0.99,0.93]),sunsoft[...,None]*0.55)
save(day,'day')
# night: deep blue moonlit, the sun becomes a pale moon, a few stars
n=lum*np.array([0.32,0.40,0.70])+a*0.08
n=n*mix(1.0,0.55,np.clip(1-yy,0,1))
n=mix(n,np.array([0.80,0.84,0.95]),np.clip(sun[...,None]*1.2,0,1)*0.9)
n=mix(n,np.array([0.55,0.62,0.85]),sunsoft[...,None]*0.18)
rng=np.random.default_rng(4); st=np.zeros((H,W),np.float32)
for _ in range(140):
    x,y=rng.integers(0,W),rng.integers(0,int(H*0.36))
    st[y,x]=0.5+0.5*rng.random()**2
st=np.asarray(Image.fromarray((st*255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.8))).astype(np.float32)/255*3
n=mix(n,np.array([1,1,1]),np.clip(st,0,1)[...,None]*np.clip(1-cl*1.5,0,1))
save(n,'night')
