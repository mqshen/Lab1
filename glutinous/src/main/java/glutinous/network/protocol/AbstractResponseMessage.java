package glutinous.network.protocol;

import glutinous.network.buffer.ManagedBuffer;

public abstract class AbstractResponseMessage extends AbstractMessage implements ResponseMessage {

    protected AbstractResponseMessage(ManagedBuffer body, boolean isBodyInFrame) {
        super(body, isBodyInFrame);
    }

    public abstract ResponseMessage createFailureResponse(String error);
}
