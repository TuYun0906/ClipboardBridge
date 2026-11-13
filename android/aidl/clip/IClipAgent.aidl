package clip;

import clip.IClipEvents;

interface IClipAgent {
    boolean watch(IClipEvents events) = 0;
    void destroy() = 16777114;
}
