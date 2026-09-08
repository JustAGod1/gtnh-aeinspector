package com.aeinspector.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.StatCollector;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import appeng.api.util.DimensionalCoord;
import appeng.client.render.highlighter.BlockPosHighlighter;
import com.aeinspector.storage.HistoryQuery;

/** Client-only presentation; live snapshots arrive once per second, user requests receive immediate replies. */
public final class InspectorScreen extends GuiContainer {
    private static final int[] COLORS = {0x69dfb6,0x79bfff,0xf2c879,0xd59aff,0xff959d,0x71dce1,0xc8dd82,0xc9c9ff};
    private static final String[] SCALES = {"5s","1m","10m","1h","10h","50h","250h","1000h"};
    private static int nextSequence;
    private final InspectorProtocol.Request request;
    private final GuiRequestState requests = new GuiRequestState();
    private final GraphTimeline timeline = new GraphTimeline();
    private final ClientRowList table = new ClientRowList();
    private final InspectorScreen statistics;
    private final boolean devicesView;
    private final String resourceName;
    private NBTTagCompound data = new NBTTagCompound();
    private List<GraphSeries> graphs = new ArrayList<>(), previous = new ArrayList<>();
    private GuiTextField search;
    private InspectorLayout layout;
    private InspectorLayout.Controls controls;
    private SnapshotBatch receiving;
    private NBTTagList deferredRows;
    private boolean refreshNeeded, resizeNeeded, dragging, resizingTable, switchingView, backgroundFirst;
    private int dividerGrab;
    private int scaleOverride, scaleFactor;
    private int dragOffset, dragGrab, mouseX, mouseY;
    private long searchDue, lastFrame, frameNow, lastSnapshot;
    private double axis;
    private double displayEnd;
    private String failure;

    public InspectorScreen(InspectorContainer container) {
        this(container,InspectorClientSettings.restore(Minecraft.getMinecraft().mcDataDir,Minecraft.getMinecraft().getNetHandler()),null,"");
    }
    private InspectorScreen(InspectorContainer container,InspectorProtocol.Request request,InspectorScreen statistics,String name) {
        super(container); this.request=request; this.statistics=statistics; devicesView=statistics!=null; resourceName=name;
    }
    private static String tr(String key) { return StatCollector.translateToLocal("aeinspector."+key); }
    @Override public void setWorldAndResolution(Minecraft minecraft,int ignoredWidth,int ignoredHeight) {
        scaleOverride=InspectorClientSettings.scale(minecraft.mcDataDir);
        InspectorScale scaled=new InspectorScale(scaleOverride,minecraft.gameSettings.guiScale,minecraft.displayWidth,minecraft.displayHeight,minecraft.func_152349_b());
        scaleFactor=scaled.factor;
        super.setWorldAndResolution(minecraft,scaled.width,scaled.height);
    }
    @Override public void initGui() {
        xSize=Math.min(900,width-20); ySize=Math.min(600,height-20); super.initGui();
        layout=new InspectorLayout(ySize,devicesView,InspectorClientSettings.tableRows(mc.mcDataDir)); request.rows=Math.min(layout.rows,InspectorProtocol.PAGE_SIZE);
        Keyboard.enableRepeatEvents(true);
        controls=new InspectorLayout.Controls(xSize);
        search=new GuiTextField(fontRendererObj,guiLeft+12,guiTop+30,controls.searchWidth,16);
        search.setMaxStringLength(128); search.setText(request.search); buttonList.clear();
        if(devicesView) button(5,12,29,65,19,tr("back"));
        else {
            button(6,controls.sortLeft,29,controls.sortWidth,19,tr("sort."+request.sort));
            button(9,controls.filterLeft,29,controls.filterWidth,19,tr("filter."+request.filter));
        }
        button(7,xSize-72,4,60,16,tr("retry"));
        button(8,controls.scaleLeft,29,controls.scaleWidth,19,tr("gui_scale")+": "+(scaleOverride==0?tr("game_scale"):scaleFactor+"×"));
        for(int i=0;i<9;i++) {
            int left=12+i*(xSize-24)/9,right=12+(i+1)*(xSize-24)/9;
            button(20+i,left,54,right-left-3,18,i==8?tr("all"):SCALES[i]);
        }
        if(!devicesView) for(int i=0;i<layout.rows;i++) button(100+i,rowsRight()-32,layout.rowsTop+i*layout.rowHeight+1,30,layout.rowHeight-2,"I/O");
        refreshNeeded=data.hasNoTags()&&!requests.pending(); dragging=false; resizingTable=false;
        if(deferredRows!=null) { replaceRows(deferredRows); deferredRows=null; }
        if(!data.hasNoTags()) scrollTo(offset());
        refreshControls();
    }
    private void button(int id,int x,int y,int w,int h,String label) { buttonList.add(new InspectorButton(id,guiLeft+x,guiTop+y,w,h,label)); }
    private InspectorProtocol.Request copyRequest() {
        InspectorProtocol.Request copy=request.copy();
        copy.window=inventorySlots.windowId; copy.devices=devicesView; return copy;
    }
    private void sendRequest() {
        if(requests.pending()) return;
        request.sequence=++nextSequence; searchDue=0; refreshNeeded=false;
        receiving=null;
        requests.begin(request.sequence,System.nanoTime()); refreshControls();
        InspectorProtocol.expectSnapshot(devicesView,inventorySlots.windowId,request.sequence);
        InspectorProtocol.CHANNEL.sendToServer(copyRequest());
    }
    private void refreshControls() {
        boolean busy=requests.pending();
        for(Object object:buttonList) {
            if(!(object instanceof InspectorButton)) continue;
            InspectorButton b=(InspectorButton)object;
            b.enabled=!busy;
            if(b.id>=20&&b.id<29) { b.selected=b.id-20==request.level; b.enabled=!busy&&!b.selected; }
            if(b.id>=100) b.visible=b.id-100<rows().tagCount();
            if(b.id==5||b.id==8) b.enabled=true;
            if(b.id==7) { b.visible=requests.slow(System.nanoTime()); b.enabled=b.visible; }
        }
        search.setEnabled(!busy);
    }
    @Override public void updateScreen() {
        if(resizeNeeded) { resizeNeeded=false; setWorldAndResolution(mc,width,height); }
        super.updateScreen();
        if(mc.currentScreen!=this) return;
        search.updateCursorCounter(); long deadline=System.nanoTime()+2_000_000L;
        // Share the existing decode budget and alternate priority; a large hidden table cannot starve I/O or vice versa.
        if(statistics!=null&&(backgroundFirst=!backgroundFirst)) {
            statistics.updateData(deadline); updateData(deadline);
        } else {
            updateData(deadline); if(statistics!=null) statistics.updateData(deadline);
        }
    }
    private void updateData(long deadline) {
        long now=System.nanoTime();
        if(refreshNeeded&&!requests.pending()) { refreshNeeded=false; sendRequest(); }
        if(searchDue!=0&&now>=searchDue&&!requests.pending()) sendRequest();
        if(System.nanoTime()>=deadline) { refreshControls(); return; }
        if(receiving==null) receiving=InspectorProtocol.takeSnapshot(devicesView);
        if(receiving!=null) try {
            if(receiving.window!=inventorySlots.windowId||receiving.sequence!=request.sequence) { receiving=null; return; }
            NBTTagCompound next=null;
            do { next=receiving.decodeStep(); } while(next==null&&System.nanoTime()<deadline);
            if(next==null) { refreshControls(); return; }
            receiving=null;
            if(searchDue!=0||next.getInteger("window")!=inventorySlots.windowId||next.getInteger("sequence")!=request.sequence) return;
            List<GraphSeries> decoded=new ArrayList<>(); NBTTagList list=next.getTagList("graphs",10);
            for(int i=0;i<list.tagCount();i++) decoded.add(new GraphSeries(list.getCompoundTagAt(i)));
            if(!requests.accept(next.getInteger("sequence"),next.getLong("tick"))) return;
            boolean reset=data.hasNoTags()||data.getInteger("level")!=next.getInteger("level")||data.getInteger("network")!=next.getInteger("network")
                    ||!Arrays.equals(data.getIntArray("selected"),next.getIntArray("selected"));
            List<GraphSeries> retained=new ArrayList<>();
            if(!reset) {
                double earliest=data.getInteger("level")==8?0:timeline.position(now)-HistoryQuery.DURATIONS[data.getInteger("level")];
                for(GraphSeries series:graphs) retained.add(GraphSeries.retain(old(series.id),series,earliest));
            }
            previous=retained; graphs=decoded;
            if(reset) axis=0;
            timeline.accept(next.getLong("tick"),now,reset); lastSnapshot=now;
            data=next; failure=null;
            if(request.selected.length==0) request.selected=next.getIntArray("selected");
            NBTTagList updated=next.getTagList(devicesView?"devices":"resources",10);
            if(dragging||resizingTable) deferredRows=updated; else replaceRows(updated);
        } catch(IOException|IllegalArgumentException e) { receiving=null; failure=tr("read_error"); }
        refreshControls();
    }
    @Override protected void actionPerformed(GuiButton b) {
        if(b.id==5) { showStatistics(); return; }
        if(b.id==8) {
            InspectorClientSettings.setScale(InspectorScale.next(scaleOverride,mc.displayWidth,mc.displayHeight,mc.func_152349_b()));
            resizeNeeded=true; return;
        }
        if(b.id==7) { InspectorProtocol.CHANNEL.sendToServer(copyRequest()); return; }
        if(requests.pending()||dragging||resizingTable) return;
        if(b.id>=100) {
            NBTTagCompound row=rows().getCompoundTagAt(b.id-100); InspectorProtocol.Request next=copyRequest();
            next.selected=new int[]{row.getInteger("id")}; next.deviceOffset=0;
            switchTo(new InspectorScreen((InspectorContainer)inventorySlots,next,this,row.getString("name"))); return;
        }
        if(b.id>=20&&b.id<29) request.level=b.id-20;
        else if(b.id==6) { request.sort=(request.sort+1)%3; request.resourceOffset=0; b.displayString=tr("sort."+request.sort); }
        else if(b.id==9) { request.filter=(request.filter+1)%3; request.resourceOffset=0; request.selected=new int[0]; b.displayString=tr("filter."+request.filter); }
        sendRequest();
    }
    private void switchTo(InspectorScreen next) {
        switchingView=true;
        try { mc.displayGuiScreen(next); }
        finally { switchingView=false; }
    }
    private void showStatistics() {
        if(statistics==null) return;
        // Restore the original screen and its live data, selection and local scroll; no new main query.
        switchTo(statistics);
        if(mc.currentScreen!=statistics) return;
        InspectorProtocol.Request stop=copyRequest(); stop.sequence=++nextSequence; stop.subscribe=false;
        InspectorProtocol.closeSnapshots(true); receiving=null;
        InspectorProtocol.CHANNEL.sendToServer(stop);
    }
    @Override protected void keyTyped(char character,int key) {
        if(key==Keyboard.KEY_ESCAPE) { if(devicesView) showStatistics(); else super.keyTyped(character,key); return; }
        if(requests.pending()) return;
        if(!devicesView&&search.textboxKeyTyped(character,key)) { request.search=search.getText(); request.resourceOffset=0; searchDue=System.nanoTime()+250_000_000L; }
        else super.keyTyped(character,key);
    }
    private NBTTagList rows() { return table.page(offset(),layout.rows); }
    private int count() { return table.count(); }
    private int offset() { return devicesView?request.deviceOffset:request.resourceOffset; }
    private void replaceRows(NBTTagList updated) {
        int position=table.replace(updated,offset(),layout.rows);
        if(devicesView) request.deviceOffset=position; else request.resourceOffset=position;
    }
    private void scrollTo(int value) {
        value=ScrollWindow.clamp(value,count(),layout.rows); if(value==offset()) return;
        if(devicesView) request.deviceOffset=value; else request.resourceOffset=value;
        refreshControls();
    }
    private int trackLeft() { return xSize-InspectorLayout.SCROLL_MARGIN-InspectorLayout.SCROLL_WIDTH; }
    private int rowsRight() { return trackLeft()-5; }
    private int trackHeight() { return layout.rows*layout.rowHeight; }
    private int thumbHeight() { return Math.min(trackHeight(),Math.max(24,(int)(trackHeight()*(double)layout.rows/Math.max(layout.rows,count())))); }
    private int thumbTop() {
        int position=dragging?dragOffset:offset();
        return layout.rowsTop+(int)((trackHeight()-thumbHeight())*position/(double)Math.max(1,count()-layout.rows));
    }
    @Override public void handleMouseInput() {
        if(devicesView&&Mouse.getEventButton()==3) {
            if(Mouse.getEventButtonState()) showStatistics();
            return;
        }
        super.handleMouseInput(); int wheel=Mouse.getEventDWheel();
        int x=Mouse.getEventX()*width/mc.displayWidth-guiLeft,y=height-Mouse.getEventY()*height/mc.displayHeight-1-guiTop;
        if(wheel!=0&&!dragging&&!resizingTable&&x>=8&&x<xSize-6&&y>=layout.rowsTop&&y<layout.rowsTop+trackHeight()) scrollTo(offset()+(wheel>0?-1:1));
    }
    @Override protected void mouseClicked(int mx,int my,int button) {
        int x=mx-guiLeft,y=my-guiTop;
        if(!devicesView&&x>=10&&x<xSize-10&&Math.abs(y-layout.dividerY())<=5) {
            if(button==0) { resizingTable=true; dividerGrab=y-layout.dividerY(); return; }
            if(button==1) { resizeTable(0); InspectorClientSettings.setTableRows(0); return; }
        }
        if(button==0&&x>=trackLeft()-2&&x<xSize-4&&y>=layout.rowsTop&&y<layout.rowsTop+trackHeight()&&count()>layout.rows) {
            int thumb=thumbTop(); dragGrab=y>=thumb&&y<thumb+thumbHeight()?y-thumb:thumbHeight()/2;
            dragging=true; dragOffset=offset(); dragAt(y); return;
        }
        super.mouseClicked(mx,my,button);
        if(mc.currentScreen!=this||resizeNeeded||requests.pending()||dragging||resizingTable) return;
        if(!devicesView) search.mouseClicked(mx,my,button);
        if(button!=0||y<layout.rowsTop||y>=layout.rowsTop+trackHeight()||x<10||x>=rowsRight()) return;
        int index=(y-layout.rowsTop)/layout.rowHeight; if(index>=rows().tagCount()) return;
        NBTTagCompound row=rows().getCompoundTagAt(index);
        if(devicesView) {
            if(row.getInteger("id")==0) return;
            BlockPosHighlighter.highlightBlocks(mc.thePlayer,Collections.singletonList(new DimensionalCoord(row.getInteger("x"),row.getInteger("y"),row.getInteger("z"),row.getInteger("dim"))),"aeinspector.highlighted","aeinspector.other_dimension");
            mc.thePlayer.closeScreen();
        } else if(x<rowsRight()-34) {
            int id=row.getInteger("id");
            if(!isShiftKeyDown()) request.selected=new int[]{id};
            else {
                List<Integer> selected=new ArrayList<>(); for(int value:request.selected) selected.add(value);
                if(selected.contains(id)) { if(selected.size()>1) selected.remove(Integer.valueOf(id)); } else if(selected.size()<8) selected.add(id);
                request.selected=new int[selected.size()]; for(int i=0;i<selected.size();i++) request.selected[i]=selected.get(i);
            }
            sendRequest();
        }
    }
    private void dragAt(int y) {
        dragOffset=ScrollWindow.at((y-layout.rowsTop-dragGrab)/(double)Math.max(1,trackHeight()-thumbHeight()),count(),layout.rows);
        scrollTo(dragOffset);
    }
    private void resizeTable(int wantedRows) {
        InspectorLayout next=new InspectorLayout(ySize,false,wantedRows);
        if(next.rows==layout.rows) return;
        layout=next; request.rows=Math.min(layout.rows,InspectorProtocol.PAGE_SIZE);
        for(java.util.Iterator<?> it=buttonList.iterator();it.hasNext();) if(((GuiButton)it.next()).id>=100) it.remove();
        for(int i=0;i<layout.rows;i++) button(100+i,rowsRight()-32,layout.rowsTop+i*layout.rowHeight+1,30,layout.rowHeight-2,"I/O");
        scrollTo(offset()); refreshControls();
    }
    @Override protected void mouseClickMove(int mx,int my,int button,long held) {
        if(resizingTable) resizeTable(layout.rowsAtDivider(ySize,my-guiTop-dividerGrab));
        else if(dragging) dragAt(my-guiTop); else super.mouseClickMove(mx,my,button,held);
    }
    @Override protected void mouseMovedOrUp(int mx,int my,int button) {
        super.mouseMovedOrUp(mx,my,button);
        if(resizingTable&&button==0) {
            resizeTable(layout.rowsAtDivider(ySize,my-guiTop-dividerGrab)); resizingTable=false;
            InspectorClientSettings.setTableRows(layout.rows);
            if(deferredRows!=null) { replaceRows(deferredRows); deferredRows=null; }
            refreshControls(); return;
        }
        if(dragging&&button==0) {
            dragAt(my-guiTop); dragging=false;
            if(deferredRows!=null) { replaceRows(deferredRows); deferredRows=null; }
            refreshControls();
        }
    }
    @Override protected void drawGuiContainerBackgroundLayer(float partial,int mx,int my) {
        frameNow=System.nanoTime(); displayEnd=timeline.position(frameNow); mouseX=mx; mouseY=my;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glDisable(GL11.GL_LIGHTING); GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glColor4f(1,1,1,1);
            rect(0,0,xSize,ySize,0xff101a22); rect(1,1,xSize-1,23,0xff1c2c37); rect(1,22,xSize-1,23,0xff344b57);
            text(devicesView?tr("devices_for")+" "+resourceName:"AE Inspector",12,8,xSize-155,0xe0edf2);
            String status=requests.pending()?tr(requests.slow(frameNow)?"waiting":"loading"):tr(frameNow-lastSnapshot>3_000_000_000L?"waiting":"live");
            int statusRight=requests.slow(frameNow)?xSize-80:xSize-12;
            int statusWidth=Math.min(108,fontRendererObj.getStringWidth(status));
            text(status,statusRight-statusWidth,8,statusWidth,requests.pending()?0xf2c879:0x69dfb6);
            if(!devicesView) {
                search.drawTextBox(); if(search.getText().isEmpty()&&!search.isFocused()) text(tr("search_hint"),16,34,controls.searchWidth-8,0x657e8e);
                updateAxis(); drawChart(0); drawChart(1); drawDivider();
            } else text(tr("highlight_hint"),88,34,xSize-196,0x93adbd);
            drawRows();
            if(requests.pending()) {
                int progress=(int)((frameNow/8_000_000L)%Math.max(1,xSize-70));
                rect(1,23,xSize-1,25,0xff243641); rect(progress+1,23,Math.min(xSize-1,progress+70),25,0xff64cbb2);
                if(!devicesView) rect(8,layout.graphTop-12,xSize-8,layout.graphTop+layout.graphHeight,0x55101a22);
            }
            if(failure!=null) text(failure,12,ySize-17,xSize-24,0xff959d);
        } finally { GL11.glPopAttrib(); lastFrame=frameNow; }
    }
    private void drawRows() {
        int incoming=InspectorLayout.incomingColumn(xSize,devicesView),outgoing=InspectorLayout.outgoingColumn(xSize,devicesView);
        text(tr(devicesView?"device_column":"resource_column"),12,layout.rowsTop-13,incoming-20,0x94adbc);
        text(tr("incoming"),incoming,layout.rowsTop-13,outgoing-incoming-6,0x69dfb6);
        text(tr("outgoing"),outgoing,layout.rowsTop-13,rowsRight()-outgoing-34,0xf2b48e);
        NBTTagList list=rows(); double maximum=1;
        for(int i=0;i<list.tagCount();i++) maximum=Math.max(maximum,Math.max(list.getCompoundTagAt(i).getLong("in"),list.getCompoundTagAt(i).getLong("out")));
        for(int i=0;i<Math.min(layout.rows,list.tagCount());i++) {
            NBTTagCompound row=list.getCompoundTagAt(i); int y=layout.rowsTop+i*layout.rowHeight,id=row.getInteger("id");
            int color=color(id),selected=devicesView?-1:selection(id);
            boolean hover=mouseX>=guiLeft+10&&mouseX<guiLeft+rowsRight()&&mouseY>=guiTop+y&&mouseY<guiTop+y+layout.rowHeight;
            rect(9,y,rowsRight(),y+layout.rowHeight-1,selected>=0?0xff253e48:hover?0xff25343e:(i%2==0?0xff192832:0xff15232d));
            if(selected>=0) rect(9,y,11,y+layout.rowHeight-1,0xff000000|color);
            String name=devicesView&&id==0?tr("unknown_source"):row.getString("name"); int left=13;
            if(!devicesView) { drawIcon(row,14,y+1,Math.min(16,layout.rowHeight-2)); left=34; }
            text(name,left,y+3,incoming-left-9,selected>=0?color:0xcbdbe3);
            if(devicesView&&layout.rowHeight>=22&&id!=0) text("["+row.getInteger("x")+", "+row.getInteger("y")+", "+row.getInteger("z")+"] · "+tr("side")+" "+row.getInteger("side"),13,y+14,incoming-22,0x7893a4);
            quantity(row,"in",incoming,y,outgoing-incoming-10,maximum,0x69dfb6);
            quantity(row,"out",outgoing,y,rowsRight()-outgoing-(devicesView?7:34),maximum,0xf2b48e);
        }
        if(list.tagCount()==0) text(tr(devicesView?"no_devices":"no_resources"),13,layout.rowsTop+7,xSize-32,0x94adbc);
        boolean scrollHover=mouseX>=guiLeft+trackLeft()-2&&mouseX<guiLeft+xSize-4&&mouseY>=guiTop+layout.rowsTop&&mouseY<guiTop+layout.rowsTop+trackHeight();
        rect(trackLeft(),layout.rowsTop,xSize-6,layout.rowsTop+trackHeight(),0xff223540);
        rect(trackLeft()+2,thumbTop(),xSize-8,thumbTop()+thumbHeight(),dragging?0xffa5eed7:scrollHover?0xff8fbdc9:0xff648d9c);
        int grip=thumbTop()+thumbHeight()/2;
        for(int y=grip-3;y<=grip+3;y+=3) rect(trackLeft()+4,y,xSize-10,y+1,0xff314e5b);
        int start=dragging?dragOffset:offset(); String range=count()==0?"0 / 0":(start+1)+"–"+Math.min(count(),start+layout.rows)+" / "+count();
        text(range,12,ySize-16,100,0x94adbc);
        text(tr("scroll_hint"),118,ySize-16,xSize-130,0x6f8b9d);
    }
    private void drawDivider() {
        int y=layout.dividerY();
        boolean hover=mouseX>=guiLeft+10&&mouseX<guiLeft+xSize-10&&Math.abs(mouseY-guiTop-y)<=5;
        rect(12,y,xSize-12,y+1,hover||resizingTable?0xff649ca9:0xff304651);
        rect(xSize/2-22,y-3,xSize/2+22,y+4,0xff203641);
        for(int line=y-2;line<=y+2;line+=2) rect(xSize/2-12,line,xSize/2+12,line+1,hover||resizingTable?0xff9de2d4:0xff739ba8);
    }
    private void quantity(NBTTagCompound row,String key,int x,int y,int w,double maximum,int color) {
        if(w<=0) return;
        text(number(row.getDouble(key+"Rate")*60)+"/"+tr("minute_short"),x,y+3,w,color);
        if(layout.rowHeight>=16) {
            rect(x,y+layout.rowHeight-4,x+w,y+layout.rowHeight-2,0xff0f1c25);
            rect(x,y+layout.rowHeight-4,x+(int)(w*(double)row.getLong(key)/maximum),y+layout.rowHeight-2,0xff000000|color);
        }
    }
    private int selection(int id) { for(int i=0;i<request.selected.length;i++) if(request.selected[i]==id) return i; return -1; }
    private int color(int id) { int i=selection(id); return COLORS[i<0?0:i]; }
    private void drawIcon(NBTTagCompound row,int x,int y,int size) {
        ItemStack icon=InspectorData.icon(row); if(icon==null) return;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS); GL11.glPushMatrix();
        try { GL11.glTranslatef(guiLeft+x,guiTop+y,0); GL11.glScalef(size/16f,size/16f,1); RenderHelper.enableGUIStandardItemLighting();
            itemRender.renderItemAndEffectIntoGUI(fontRendererObj,mc.getTextureManager(),icon,0,0);
        } finally { GL11.glPopMatrix(); GL11.glPopAttrib(); }
    }
    private void rect(int x,int y,int right,int bottom,int color) { drawRect(guiLeft+x,guiTop+y,guiLeft+right,guiTop+bottom,color); }
    private void text(String text,int x,int y,int w,int color) { if(w>0) fontRendererObj.drawString(fontRendererObj.trimStringToWidth(text,w),guiLeft+x,guiTop+y,color); }

    private GraphSeries old(int id) { for(GraphSeries series:previous) if(series.id==id) return series; return null; }
    private double duration() { return data.getInteger("level")==8?Math.max(1,displayEnd):HistoryQuery.DURATIONS[data.getInteger("level")]; }
    private int chartX(int direction) { return 10+direction*xSize/2; }
    private int chartWidth() { return xSize/2-20; }
    private int axisWidth() { return 44; }
    private int plotTop() { return layout.graphTop+16; }
    private int plotBottom() { return layout.graphTop+layout.graphHeight-17; }
    /** One shared Y range, advanced once per frame before either chart is drawn. */
    private void updateAxis() {
        double maximum=1;
        for(GraphSeries series:graphs) for(int i=0;i<series.observed.length;i++) {
            if(series.time(i)>=displayEnd-duration()&&series.time(i)<=displayEnd+series.width)
                maximum=Math.max(maximum,Math.max(finite(series.rate(0,i)),finite(series.rate(1,i))));
        }
        for(GraphSeries series:previous) for(int i=0;i<series.observed.length;i++) {
            if(series.time(i)>=displayEnd-duration()&&series.time(i)<=displayEnd)
                maximum=Math.max(maximum,Math.max(finite(series.rate(0,i)),finite(series.rate(1,i))));
        }
        if(axis==0) axis=maximum;
        else axis+=(maximum-axis)*(1-Math.exp(-Math.min(100_000_000L,Math.max(0,frameNow-lastFrame))/180_000_000.0));
    }
    private void drawChart(int direction) {
        int x=chartX(direction),w=chartWidth(),left=x+axisWidth(),right=x+w-7,top=plotTop(),bottom=plotBottom();
        rect(x-1,layout.graphTop-1,x+w+1,layout.graphTop+layout.graphHeight+1,0xff30434f);
        rect(x,layout.graphTop,x+w,layout.graphTop+layout.graphHeight,0xff0d171f);
        text(tr(direction==0?"incoming":"outgoing"),x+2,layout.graphTop-12,w-4,direction==0?0x69dfb6:0xf2b48e);
        double maximum=Math.max(1,axis);
        int divisions=Math.max(1,Math.min(4,(bottom-top)/22));
        for(int i=0;i<=divisions;i++) {
            int y=top+(bottom-top)*i/divisions; rect(left,y,right,y+1,0xff263742);
            text(number(maximum*(divisions-i)/divisions),x+3,y-3,axisWidth()-8,0x7895a7);
        }
        for(int i=0;i<=4;i++) { int line=left+(right-left)*i/4; rect(line,top,line+1,bottom,0xff1a2c38); }
        text("/"+tr("second_short"),x+3,layout.graphTop+3,axisWidth()-5,0x7895a7);
        drawLegend(left,layout.graphTop+3,right-left);
        for(int i=0;i<=2;i++) {
            if(i==1&&right-left<130) continue;
            String label=i==2?tr("now"):ago(duration()*(2-i)/2);
            int px=left+(right-left)*i/2-fontRendererObj.getStringWidth(label)*i/2;
            text(label,px,bottom+6,(right-left)/2,0x7895a7);
        }
        if(graphs.isEmpty()) { text(tr("choose_resource"),left+4,top+8,right-left-8,0x94adbc); return; }
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D); GL11.glDisable(GL11.GL_LIGHTING); GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            double sx=mc.displayWidth/(double)width,sy=mc.displayHeight/(double)height;
            GL11.glScissor((int)((guiLeft+left)*sx),(int)(mc.displayHeight-(guiTop+bottom+1)*sy),Math.max(1,(int)((right-left)*sx)),Math.max(1,(int)((bottom-top+2)*sy)));
            GL11.glLineWidth(1.5f); GL11.glPointSize(2f);
            for(GraphSeries series:graphs) {
                int c=color(series.id); GL11.glColor4f((c>>16&255)/255f,(c>>8&255)/255f,(c&255)/255f,1);
                drawSeries(old(series.id),series,direction,left,right,top,bottom,maximum);
            }
        } finally { GL11.glPopAttrib(); }
        if(mouseX>=guiLeft+left&&mouseX<guiLeft+right&&mouseY>=guiTop+top&&mouseY<guiTop+bottom) {
            rect(mouseX-guiLeft,top,mouseX-guiLeft+1,bottom,0x99b0cddd);
            rect(left,mouseY-guiTop,right,mouseY-guiTop+1,0x446a8598);
        }
    }
    private void drawSeries(GraphSeries older,GraphSeries current,int direction,int left,int right,int top,int bottom,double max) {
        GraphSeries[] parts={older,current}; boolean connected=false; double lastX=0,lastY=0,lastTime=0,lastWidth=0;
        GL11.glBegin(GL11.GL_LINES);
        for(GraphSeries series:parts) if(series!=null) for(int i=0;i<series.observed.length;i++) {
            if(series==older&&series.time(i)>=current.start) continue;
            double rate=series.rate(direction,i); if(!Double.isFinite(rate)) { connected=false; continue; }
            double x=guiLeft+left+(series.time(i)-(displayEnd-duration()))*(right-left)/duration();
            double y=guiTop+bottom-1-Math.min(1,rate/max)*(bottom-top-2);
            if(connected&&series.time(i)-lastTime<=(lastWidth+series.width)/2) { GL11.glVertex2d(lastX,lastY); GL11.glVertex2d(x,y); }
            lastX=x; lastY=y; lastTime=series.time(i); lastWidth=series.width; connected=true;
        }
        GL11.glEnd();
        GL11.glBegin(GL11.GL_POINTS);
        for(GraphSeries series:parts) if(series!=null) for(int i=0;i<series.observed.length;i++) {
            if(series==older&&series.time(i)>=current.start) continue;
            double rate=series.rate(direction,i); if(!Double.isFinite(rate)) continue;
            GL11.glVertex2d(guiLeft+left+(series.time(i)-(displayEnd-duration()))*(right-left)/duration(),guiTop+bottom-1-Math.min(1,rate/max)*(bottom-top-2));
        }
        GL11.glEnd();
    }
    private void drawLegend(int left,int y,int width) {
        int remaining=graphs.size();
        for(GraphSeries series:graphs) {
            if(width<38) { text("+"+remaining,left,y,width,0x94adbc); break; }
            int cell=Math.min(130,width/Math.min(3,remaining));
            rect(left,y+2,left+3,y+6,0xff000000|color(series.id));
            text(series.name,left+6,y,cell-12,color(series.id));
            left+=cell; width-=cell; remaining--;
        }
    }
    private static double finite(double value) { return Double.isFinite(value)?value:0; }
    private static String number(double value) {
        if(!Double.isFinite(value)) return "—";
        if(value>=1_000_000_000) return String.format(Locale.ROOT,"%.1fG",value/1_000_000_000);
        if(value>=1_000_000) return String.format(Locale.ROOT,"%.1fM",value/1_000_000);
        if(value>=1000) return String.format(Locale.ROOT,"%.1fk",value/1000);
        return String.format(Locale.ROOT,value==Math.rint(value)?"%.0f":"%.1f",value);
    }
    private static String ago(double ticks) {
        double seconds=ticks/20; return seconds>=3600?number(seconds/3600)+"h":seconds>=60?number(seconds/60)+"m":number(seconds)+"s";
    }
    @Override public void drawScreen(int mx,int my,float partial) {
        // Own projection and mouse coordinates, including GuiContainer and its tooltips.
        // Restore both matrix stacks so the HUD and every other screen retain the game's GUI scale.
        int matrix=GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPushMatrix(); GL11.glLoadIdentity();
        GL11.glOrtho(0,width,height,0,1000,3000);
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPushMatrix(); GL11.glLoadIdentity(); GL11.glTranslatef(0,0,-2000);
        try { drawInspector(Mouse.getX()*width/mc.displayWidth,height-Mouse.getY()*height/mc.displayHeight-1,partial); }
        finally {
            GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPopMatrix(); GL11.glMatrixMode(matrix);
        }
    }
    private void drawInspector(int mx,int my,float partial) {
        super.drawScreen(mx,my,partial); int x=mx-guiLeft,y=my-guiTop;
        if(devicesView&&x>=12&&x<77&&y>=29&&y<48) {
            drawHoveringText(Collections.singletonList(tr("back_hint")),mx,my,fontRendererObj); return;
        }
        if(!devicesView&&!resizingTable&&x>=10&&x<xSize-10&&Math.abs(y-layout.dividerY())<=5) {
            drawHoveringText(Arrays.asList(tr("resize_table"),tr("reset_table")),mx,my,fontRendererObj); return;
        }
        if(x>=controls.scaleLeft&&x<xSize-12&&y>=29&&y<48) {
            drawHoveringText(Arrays.asList(tr("gui_scale_hint"),tr("gui_scale_cycle")),mx,my,fontRendererObj); return;
        }
        if(!devicesView&&y>=plotTop()&&y<plotBottom()&&!requests.pending()) {
            int direction=x>=xSize/2?1:0,left=chartX(direction)+axisWidth(),right=chartX(direction)+chartWidth()-7;
            if(x>=left&&x<right) {
                double tick=displayEnd-duration()+(x-left)*duration()/(right-left);
                List<String> tip=new ArrayList<>(); tip.add(tr(direction==0?"incoming":"outgoing")+" · "+ago(Math.max(0,displayEnd-tick))+" "+tr("ago"));
                for(GraphSeries current:graphs) {
                    GraphSeries series=tick<current.start?old(current.id):current;
                    if(series==null) continue; int bucket=series.bucket(tick);
                    if(bucket<0||bucket>=series.observed.length) continue;
                    tip.add(series.name+" #"+series.id);
                    if(series.observed[bucket]==0) { tip.add(tr("unobserved")); continue; }
                    String unit=series.fluid?"mB":tr("items");
                    tip.add(number(series.rate(direction,bucket))+" "+unit+"/"+tr("second_short")+" · "+tr("exact")+" "+series.counts[direction][bucket]+" · ~"+series.counts[direction+2][bucket]);
                }
                if(tip.size()>1) drawHoveringText(tip,mx,my,fontRendererObj); return;
            }
        }
        if(x<10||x>=rowsRight()||y<layout.rowsTop||y>=layout.rowsTop+trackHeight()||dragging||resizingTable) return;
        int index=(y-layout.rowsTop)/layout.rowHeight; if(index>=rows().tagCount()) return;
        NBTTagCompound row=rows().getCompoundTagAt(index); String unit=(devicesView?data.getCompoundTag("deviceResource"):row).getBoolean("fluid")?"mB":tr("items");
        List<String> tip=new ArrayList<>(); tip.add(devicesView&&row.getInteger("id")==0?tr("unknown_source"):row.getString("name"));
        tip.add(tr("incoming")+": "+row.getLong("in")+" "+unit+" · "+number(row.getDouble("inRate")*60)+" "+unit+"/"+tr("minute_short"));
        tip.add(tr("outgoing")+": "+row.getLong("out")+" "+unit+" · "+number(row.getDouble("outRate")*60)+" "+unit+"/"+tr("minute_short"));
        if(devicesView) {
            if(row.getInteger("id")==0) tip.add(tr("unknown_hint"));
            else { tip.add("["+row.getInteger("x")+", "+row.getInteger("y")+", "+row.getInteger("z")+"] dim "+row.getInteger("dim")+" · "+tr("side")+" "+row.getInteger("side")); tip.add(tr("highlight_hint")); }
            long[] counts=InspectorData.longs(row.getByteArray("windowCounts"));
            if(counts.length==4) { tip.add(tr("exact")+" +"+counts[0]+" / -"+counts[1]); tip.add(tr("estimated")+" +"+counts[2]+" / -"+counts[3]); }
            long[] totals=InspectorData.longs(row.getByteArray("totals"));
            if(totals.length==4) {
                tip.add(tr("all_time_counts"));
                tip.add(tr("exact")+" +"+totals[0]+" / -"+totals[1]); tip.add(tr("estimated")+" +"+totals[2]+" / -"+totals[3]);
            }
            if(row.getBoolean("configured")) tip.add(tr("configured"));
            if(row.getBoolean("stored")) tip.add(tr("stored_here"));
            tip.add(tr(row.getBoolean("active")?"node_online":"node_offline"));
        } else { tip.add(row.getString("registry")+":"+row.getInteger("meta")+" #"+row.getInteger("id")); tip.add(tr(x>=rowsRight()-34?"io_hint":"click_hint")); }
        drawHoveringText(tip,mx,my,fontRendererObj);
    }
    @Override public void onGuiClosed() {
        if(switchingView) return; // Navigation shares the container and keeps the main subscription alive.
        if(resizingTable) InspectorClientSettings.setTableRows(layout.rows);
        InspectorScreen main=statistics==null?this:statistics;
        InspectorClientSettings.remember(mc.getNetHandler(),main.request);
        main.receiving=null; main.deferredRows=null;
        receiving=null; deferredRows=null; InspectorProtocol.closeSnapshots();
        Keyboard.enableRepeatEvents(false); super.onGuiClosed();
    }
}
