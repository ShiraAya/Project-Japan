package com.sora.pjr.validation;

import com.sora.pjr.core.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;

/** A sampled plan of actual final column ownership, clearly not an in-game screenshot. */
final class UrbanCoastPreview {
  static void render(WorldEngine engine)throws Exception {
    String[] names={"Tokyo Bay","Osaka - Kobe","Nagoya","Fukuoka","Hiroshima","Naha","Shizuoka","Hamamatsu","Niigata"};
    double[][] areas={{139.64,35.40,140.08,35.73},{135.12,34.35,135.55,34.75},{136.60,34.78,136.98,35.15},{130.28,33.58,130.45,33.71},{132.36,34.28,132.55,34.40},{127.61,26.12,127.73,26.29},{138.36,34.86,138.59,35.10},{137.50,34.62,137.92,34.82},{138.91,37.84,139.31,38.04}};
    int w=360,h=310,pad=16,head=70;
    BufferedImage out=new BufferedImage((w+pad)*3+pad,(h+40+pad)*3+head,BufferedImage.TYPE_INT_RGB);
    Graphics2D g=out.createGraphics();g.setColor(new Color(20,29,40));g.fillRect(0,0,out.getWidth(),out.getHeight());
    g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,20));g.drawString("PJR 0.1.16 | GENERATED SURFACE PLAN",pad,28);
    g.setFont(new Font("SansSerif",Font.PLAIN,13));g.drawString("0.5 : 0.5 : 0.5  /  blue: ocean  /  cyan: enabled river or lake  /  not an in-game screenshot",pad,51);
    for(int i=0;i<names.length;i++) {
      int ox=pad+(i%3)*(w+pad),oy=head+(i/3)*(h+40+pad);double[] a=areas[i];
      g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,16));g.drawString(names[i],ox,oy+18);
      for(int row=0;row<h;row++)for(int col=0;col<w;col++) {
        double lon=a[0]+(a[2]-a[0])*(col+.5)/w,lat=a[3]-(a[3]-a[1])*(row+.5)/h;
        Geometry v=engine.geometry((int)Math.round(Geo.x(lon)),(int)Math.round(Geo.z(lat)));
        int rgb;
        if(v.kind()==Hydrology.Kind.OCEAN)rgb=0x397aa8;
        else if(v.wet())rgb=0x41bec6;
        else {int shade=(int)Math.min(55,Math.max(0,v.groundY())*.18);rgb=((184-shade)<<16)|((194-shade)<<8)|(150-shade/2);}
        out.setRGB(ox+col,oy+28+row,rgb);
      }
    }
    g.dispose();Files.createDirectories(Path.of("docs"));ImageIO.write(out,"png",Path.of("docs/urban-coast-preview.png").toFile());
  }
}
