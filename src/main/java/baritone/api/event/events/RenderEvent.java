package baritone.api.event.events;
/** Server compatibility event; render payloads are opaque and never dispatched. */
public final class RenderEvent {
    private final float partialTicks; private final Object modelView,projection;
    public RenderEvent(float partialTicks,Object modelView,Object projection){
        this.partialTicks=partialTicks;this.modelView=modelView;this.projection=projection;
    }
    public float getPartialTicks(){return partialTicks;}
    public Object getModelViewStack(){return modelView;}
    public Object getProjectionMatrix(){return projection;}
}
