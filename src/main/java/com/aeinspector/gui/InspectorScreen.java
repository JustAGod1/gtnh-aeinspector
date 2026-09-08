package com.aeinspector.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.StatCollector;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;
import appeng.api.util.DimensionalCoord;
import appeng.client.render.highlighter.BlockPosHighlighter;

/** Independent statistics and I/O views sharing the same validated wireless container. */
public final class InspectorScreen extends GuiContainer {
    private static final int[] COLORS={0x65dd95,0x74b9ff,0xfac66b,0xdb91ff,0xff8b93,0x71e4da,0xe1e58c,0xc9c9ff};
    private static final String[] SCALES={"5s","1m","10m","1h","10h","50h","250h","1000h","All"};
    private final InspectorProtocol.Request request;
    private final boolean devicesView;
    private final String resourceName;
    private NBTTagCompound data=new NBTTagCompound();
    private GuiTextField search;
    private int graphTop,graphHeight,rowsTop,rowHeight;
    private boolean initialRequest;
    private String failure;

    public InspectorScreen(InspectorContainer container) { this(container,new InspectorProtocol.Request(),false,""); }
    private InspectorScreen(InspectorContainer container,InspectorProtocol.Request request,boolean devices,String name) {
        super(container); this.request=request; devicesView=devices; resourceName=name;
    }
    private static String tr(String key) { return StatCollector.translateToLocal("aeinspector."+key); }
    @Override public void initGui() {
        xSize=Math.min(900,width-20); ySize=Math.min(600,height-20); super.initGui();
        graphTop=87; graphHeight=Math.max(40,(ySize-170)/2-20);
        rowsTop=devicesView?87:graphTop+graphHeight+30;
        rowHeight=Math.max(10,Math.min(devicesView?25:24,(ySize-rowsTop-29)/10));
        Keyboard.enableRepeatEvents(true);
        search=new GuiTextField(fontRendererObj,guiLeft+8,guiTop+29,Math.max(80,xSize/3),17);
        search.setMaxStringLength(128); search.setText(request.search); buttonList.clear();
        if(devicesView) buttonList.add(new GuiButton(5,guiLeft+8,guiTop+29,75,18,tr("back")));
        else buttonList.add(new GuiButton(6,guiLeft+xSize/3+24,guiTop+28,Math.min(170,xSize/3),20,tr("sort."+request.sort)));
        for(int i=0;i<9;i++) {
            int left=8+i*(xSize-16)/9,right=8+(i+1)*(xSize-16)/9;
            GuiButton b=new GuiButton(20+i,guiLeft+left,guiTop+54,right-left-2,18,i==8?tr("all"):SCALES[i]);
            b.enabled=i!=request.level; buttonList.add(b);
        }
        buttonList.add(new GuiButton(1,guiLeft+8,guiTop+ySize-24,22,18,"<"));
        buttonList.add(new GuiButton(2,guiLeft+xSize-30,guiTop+ySize-24,22,18,">"));
        if(!devicesView) for(int i=0;i<10;i++) {
            GuiButton b=new GuiButton(100+i,guiLeft+xSize-38,guiTop+rowsTop+i*rowHeight,30,Math.max(10,rowHeight-1),"I/O");
            b.visible=false; buttonList.add(b);
        }
        initialRequest=false;
    }
    private InspectorProtocol.Request copyRequest() {
        InspectorProtocol.Request copy=new InspectorProtocol.Request();
        copy.window=inventorySlots.windowId; copy.level=request.level; copy.sort=request.sort; copy.search=request.search;
        copy.resourcePage=request.resourcePage; copy.devicePage=request.devicePage; copy.selected=request.selected.clone(); return copy;
    }
    private void sendRequest() { InspectorProtocol.CHANNEL.sendToServer(copyRequest()); }
    @Override public void updateScreen() {
        super.updateScreen(); search.updateCursorCounter();
        // FML assigns the container window ID after initGui.
        if(!initialRequest) { initialRequest=true; sendRequest(); }
        InspectorProtocol.Snapshot response=InspectorProtocol.takeSnapshot();
        if(response!=null) try {
            NBTTagCompound next=response.decode();
            if(next.getInteger("window")!=inventorySlots.windowId) return;
            if(devicesView && (request.selected.length==0 || !next.hasKey("deviceResource") || next.getCompoundTag("deviceResource").getInteger("id")!=request.selected[0])) return;
            data=next; failure=null;
            if(request.selected.length==0) request.selected=next.getIntArray("selected");
            request.resourcePage=next.getInteger("resourcePage"); request.devicePage=next.getInteger("devicePage");
            for(Object object:buttonList) { GuiButton b=(GuiButton)object; if(b.id>=100) b.visible=b.id-100<data.getTagList("resources",10).tagCount(); }
        } catch(IOException e) { failure=tr("read_error"); }
    }
    @Override protected void actionPerformed(GuiButton b) {
        if(b.id>=100) {
            NBTTagCompound row=data.getTagList("resources",10).getCompoundTagAt(b.id-100);
            InspectorProtocol.Request next=copyRequest(); next.selected=new int[]{row.getInteger("id")}; next.devicePage=0;
            mc.displayGuiScreen(new InspectorScreen((InspectorContainer)inventorySlots,next,true,row.getString("name"))); return;
        }
        if(b.id>=20 && b.id<29) {
            request.level=b.id-20;
            for(Object object:buttonList) { GuiButton other=(GuiButton)object; if(other.id>=20&&other.id<29) other.enabled=other.id!=b.id; }
        } else if(b.id==5) { showStatistics(); return;
        } else if(b.id==6) { request.sort=(request.sort+1)%3; request.resourcePage=0; b.displayString=tr("sort."+request.sort);
        } else if(b.id==1) { if(devicesView) request.devicePage=Math.max(0,request.devicePage-1); else request.resourcePage=Math.max(0,request.resourcePage-1);
        } else if(b.id==2) { if(devicesView) request.devicePage++; else request.resourcePage++; }
        sendRequest();
    }
    private void showStatistics() { mc.displayGuiScreen(new InspectorScreen((InspectorContainer)inventorySlots,copyRequest(),false,"")); }
    @Override protected void keyTyped(char character,int key) {
        if(!devicesView && search.textboxKeyTyped(character,key)) { request.search=search.getText(); request.resourcePage=0; sendRequest(); }
        else if(devicesView && key==Keyboard.KEY_ESCAPE) showStatistics(); else super.keyTyped(character,key);
    }
    @Override protected void mouseClicked(int mx,int my,int button) {
        super.mouseClicked(mx,my,button); if(!devicesView) search.mouseClicked(mx,my,button);
        int x=mx-guiLeft,y=my-guiTop;
        if(button!=0 || y<rowsTop || y>=rowsTop+10*rowHeight || x<8 || x>=xSize-8) return;
        NBTTagList rows=data.getTagList(devicesView?"devices":"resources",10); int index=(y-rowsTop)/rowHeight;
        if(index>=rows.tagCount()) return;
        NBTTagCompound row=rows.getCompoundTagAt(index);
        if(devicesView) {
            if(row.getInteger("id")==0) return;
            BlockPosHighlighter.highlightBlocks(mc.thePlayer,Collections.singletonList(new DimensionalCoord(row.getInteger("x"),row.getInteger("y"),row.getInteger("z"),row.getInteger("dim"))),"aeinspector.highlighted","aeinspector.other_dimension");
            mc.thePlayer.closeScreen();
        } else if(x<xSize-40) {
            int id=row.getInteger("id");
            if(!isShiftKeyDown()) request.selected=new int[]{id};
            else {
                List<Integer> selected=new ArrayList<>(); for(int value:request.selected) selected.add(value);
                if(selected.contains(id)) { if(selected.size()>1) selected.remove(Integer.valueOf(id)); }
                else if(selected.size()<8) selected.add(id);
                request.selected=new int[selected.size()]; for(int i=0;i<selected.size();i++) request.selected[i]=selected.get(i);
            }
            sendRequest();
        }
    }
    @Override protected void drawGuiContainerBackgroundLayer(float partial,int mx,int my) {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glDisable(GL11.GL_LIGHTING); GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glColor4f(1,1,1,1);
            drawRect(guiLeft,guiTop,guiLeft+xSize,guiTop+ySize,0xff25292d);
            text(devicesView?tr("devices_for")+" "+resourceName:"AE Inspector",8,10,xSize-16,0xffe4af);
            if(!devicesView) { search.drawTextBox(); drawChart(0); drawChart(1); }
            else text(tr("highlight_hint"),92,34,xSize-100,0xaabac7);
            int incoming=xSize*55/100,outgoing=xSize*76/100;
            text(tr(devicesView?"device_column":"resource_column"),8,rowsTop-13,incoming-16,0xaabac7);
            text(tr("incoming"),incoming,rowsTop-13,outgoing-incoming-4,0x65dd95);
            text(tr("outgoing"),outgoing,rowsTop-13,xSize-outgoing-(devicesView?8:40),0xffae8b);
            NBTTagList rows=data.getTagList(devicesView?"devices":"resources",10); double maximum=1;
            for(int i=0;i<rows.tagCount();i++) maximum=Math.max(maximum,Math.max(rows.getCompoundTagAt(i).getLong("in"),rows.getCompoundTagAt(i).getLong("out")));
            for(int i=0;i<rows.tagCount();i++) {
                NBTTagCompound row=rows.getCompoundTagAt(i); int y=rowsTop+i*rowHeight,id=row.getInteger("id"),color=0xc8d4dd;
                for(int s=0;s<request.selected.length;s++) if(request.selected[s]==id&&!devicesView) color=COLORS[s];
                drawRect(guiLeft+7,guiTop+y,guiLeft+xSize-7,guiTop+y+rowHeight-1,0xff343a40);
                String name=devicesView&&id==0?tr("unknown_source"):row.getString("name"); int offset=8;
                if(!devicesView) { drawIcon(row,9,y+1,Math.min(16,rowHeight-2)); offset=28; }
                text(name,offset,y+2,incoming-offset-8,color);
                if(devicesView&&rowHeight>=22&&id!=0) text("["+row.getInteger("x")+", "+row.getInteger("y")+", "+row.getInteger("z")+"] · "+tr("side")+" "+row.getInteger("side"),8,y+13,incoming-16,0x8797a5);
                drawQuantity(row,"in",incoming,y,outgoing-incoming-8,maximum,0x65dd95);
                drawQuantity(row,"out",outgoing,y,xSize-outgoing-(devicesView?12:43),maximum,0xffae8b);
            }
            if(rows.tagCount()==0) text(tr(devicesView?"no_devices":"no_resources"),8,rowsTop+6,xSize-16,0xaabac7);
            int page=devicesView?request.devicePage:request.resourcePage,count=data.getInteger(devicesView?"deviceCount":"resourceCount");
            text((page+1)+" / "+Math.max(1,(count+9)/10)+(!devicesView?" · "+tr("click_hint"):""),38,ySize-19,xSize-78,0xaabac7);
            if(failure!=null) text(failure,8,75,xSize-16,0xff7777);
        } finally { GL11.glPopAttrib(); }
    }
    private void drawQuantity(NBTTagCompound row,String key,int x,int y,int w,double maximum,int color) {
        if(w<=0) return; long count=row.getLong(key);
        text(number(count)+" · "+number(row.getDouble(key+"Rate")*60)+"/"+tr("minute_short"),x,y+2,w,color);
        if(rowHeight>=14) {
            drawRect(guiLeft+x,guiTop+y+rowHeight-4,guiLeft+x+w,guiTop+y+rowHeight-2,0xff1a1d20);
            drawRect(guiLeft+x,guiTop+y+rowHeight-4,guiLeft+x+(int)(w*count/maximum),guiTop+y+rowHeight-2,0xff000000|color);
        }
    }
    private void drawIcon(NBTTagCompound row,int x,int y,int size) {
        ItemStack icon=InspectorData.icon(row); if(icon==null||size<1) return;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS); GL11.glPushMatrix();
        try { GL11.glTranslatef(guiLeft+x,guiTop+y,0); GL11.glScalef(size/16f,size/16f,1); RenderHelper.enableGUIStandardItemLighting();
            itemRender.renderItemAndEffectIntoGUI(fontRendererObj,mc.getTextureManager(),icon,0,0);
        } finally { GL11.glPopMatrix(); GL11.glPopAttrib(); }
    }
    private void text(String value,int x,int y,int max,int color) { if(max>0) fontRendererObj.drawString(fontRendererObj.trimStringToWidth(value,max),guiLeft+x,guiTop+y,color); }
    private void drawChart(int direction) {
        int x=8+direction*(xSize/2),w=xSize/2-16,left=guiLeft+x+34,right=guiLeft+x+w-4;
        int top=guiTop+graphTop+12,bottom=guiTop+graphTop+graphHeight-13;
        text(tr(direction==0?"incoming":"outgoing"),x,graphTop-11,w,0xdce4eb);
        drawRect(guiLeft+x,guiTop+graphTop,guiLeft+x+w,guiTop+graphTop+graphHeight,0xff101418);
        NBTTagList graphs=data.getTagList("graphs",10); double maximum=1; List<long[][]> series=new ArrayList<>();
        for(int g=0;g<graphs.tagCount();g++) {
            NBTTagCompound graph=graphs.getCompoundTagAt(g);
            long[][] values={InspectorData.longs(graph.getByteArray("c"+direction)),InspectorData.longs(graph.getByteArray("c"+(direction+2))),InspectorData.longs(graph.getByteArray("observed"))};
            series.add(values); for(int i=0;i<values[2].length;i++) if(values[2][i]>0) maximum=Math.max(maximum,((double)values[0][i]+values[1][i])*1200/values[2][i]);
        }
        for(int i=0;i<=4;i++) {
            int y=top+(bottom-top)*i/4; drawRect(left,y,right,y+1,0xff303941); text(number(maximum*(4-i)/4),x+2,y-guiTop-3,30,0x8d9baa);
            int xx=left+(right-left)*i/4; drawRect(xx,top,xx+1,bottom,0xff253039);
        }
        text("/"+tr("minute_short"),x+2,graphTop+1,30,0x8d9baa); text(tr("now"),x+w-26,graphTop+graphHeight-10,25,0x8d9baa);
        if(graphs.tagCount()==0) { text(tr("choose_resource"),x+38,graphTop+20,w-42,0xaabac7); return; }
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D); GL11.glDisable(GL11.GL_LIGHTING); GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_ALPHA_TEST); GL11.glLineWidth(1.5f); GL11.glPointSize(2f);
            for(int g=0;g<series.size();g++) {
                int id=graphs.getCompoundTagAt(g).getInteger("id"),color=COLORS[g%8];
                for(int s=0;s<request.selected.length;s++) if(request.selected[s]==id) color=COLORS[s];
                GL11.glColor4f((color>>16&255)/255f,(color>>8&255)/255f,(color&255)/255f,1); long[][] v=series.get(g);
                GL11.glBegin(GL11.GL_LINES);
                for(int i=1;i<v[2].length;i++) if(v[2][i-1]>0&&v[2][i]>0) { vertex(v,i-1,left,right,top,bottom,maximum); vertex(v,i,left,right,top,bottom,maximum); }
                GL11.glEnd(); GL11.glBegin(GL11.GL_POINTS);
                for(int i=0;i<v[2].length;i++) if(v[2][i]>0) vertex(v,i,left,right,top,bottom,maximum);
                GL11.glEnd();
            }
        } finally { GL11.glPopAttrib(); }
    }
    private static void vertex(long[][] v,int i,int left,int right,int top,int bottom,double max) {
        double rate=((double)v[0][i]+v[1][i])*1200/v[2][i];
        GL11.glVertex2d(left+i*(right-left-1.0)/Math.max(1,v[2].length-1),bottom-1-rate/max*(bottom-top-2));
    }
    private static String number(double value) {
        if(!Double.isFinite(value)) return "—";
        if(value>=1000000) return String.format(Locale.ROOT,"%.1fM",value/1000000);
        if(value>=1000) return String.format(Locale.ROOT,"%.1fk",value/1000);
        return String.format(Locale.ROOT,"%.1f",value);
    }
    @Override public void drawScreen(int mx,int my,float partial) {
        super.drawScreen(mx,my,partial); int x=mx-guiLeft,y=my-guiTop;
        if(x<8||x>=xSize-8||y<rowsTop||y>=rowsTop+10*rowHeight) return;
        NBTTagList rows=data.getTagList(devicesView?"devices":"resources",10); int index=(y-rowsTop)/rowHeight;
        if(index>=rows.tagCount()) return; NBTTagCompound row=rows.getCompoundTagAt(index);
        String unit=(devicesView?data.getCompoundTag("deviceResource"):row).getBoolean("fluid")?"mB":tr("items");
        List<String> tip=new ArrayList<>(); tip.add(devicesView&&row.getInteger("id")==0?tr("unknown_source"):row.getString("name"));
        tip.add(tr("incoming")+": "+row.getLong("in")+" "+unit+" · "+number(row.getDouble("inRate")*60)+" "+unit+"/"+tr("minute_short"));
        tip.add(tr("outgoing")+": "+row.getLong("out")+" "+unit+" · "+number(row.getDouble("outRate")*60)+" "+unit+"/"+tr("minute_short"));
        if(devicesView) {
            if(row.getInteger("id")==0) tip.add(tr("unknown_hint"));
            else { tip.add("["+row.getInteger("x")+", "+row.getInteger("y")+", "+row.getInteger("z")+"] dim "+row.getInteger("dim")+" · "+tr("side")+" "+row.getInteger("side")); tip.add(tr("highlight_hint")); }
            long[] counts=InspectorData.longs(row.getByteArray("windowCounts"));
            if(counts.length==4) { tip.add(tr("exact")+" +"+counts[0]+" / -"+counts[1]); tip.add(tr("estimated")+" +"+counts[2]+" / -"+counts[3]); }
            if(row.getBoolean("configured")) tip.add(tr("configured"));
        } else { tip.add(row.getString("registry")+":"+row.getInteger("meta")+" #"+row.getInteger("id")); tip.add(tr(x>=xSize-40?"io_hint":"click_hint")); }
        drawHoveringText(tip,mx,my,fontRendererObj);
    }
    @Override public void onGuiClosed() { Keyboard.enableRepeatEvents(false); super.onGuiClosed(); }
}
