package gemini3d.trace.semantics;

import java.util.List;

public interface IOSemantics<I, O, A, C> {
    List<C> initial();
    List<A> actions(I input, C configuration);
    List<Pair<O, C>> execute(A action, I input, C configuration);
}
