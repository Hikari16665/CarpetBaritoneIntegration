package baritone.api.event.events;
import baritone.api.event.events.type.Cancellable;
public final class ChatEvent extends Cancellable {
    private final String message;
    public ChatEvent(String message){this.message=message;}
    public String getMessage(){return message;}
}
