package gemini3d.trace.membership;

import gemini3d.trace.emulator.Reading;
import gemini3d.trace.semantics.IOSemantics;
import gemini3d.trace.semantics.Pair;
import gemini3d.trace.semantics.StepCip;

import org.digitaltwin.automaton.model.Automaton;
import org.digitaltwin.automaton.model.Transition;
import org.digitaltwin.automaton.semantics.Configuration;
import org.digitaltwin.automaton.str.AutomatonOutput;
import org.digitaltwin.automaton.str.AutomatonSTR;
import org.digitaltwin.automaton.str.ISemanticTransitionRelation;

import java.util.List;

/**
 * The membership expects:
 *   IOSemantics<StepCip<Reading>, AutomatonOutput, Transition, Configuration>
 *
 * AutomatonSTR provides:
 *   ISemanticTransitionRelation<StepCip<Reading>, AutomatonOutput, Transition, Configuration>
 *
 * This adapter:
 *   - Converts Set → List
 *   - Converts our Pair → IOSemantics Pair
 *   - Extracts Reading values from StepCip via the extractor
 */
public class AutomatonSLIAdapter implements IOSemantics<StepCip<Reading>, AutomatonOutput, Transition, Configuration> {

    private final AutomatonSTR<StepCip<Reading>> str;


    public AutomatonSLIAdapter(Automaton automaton) {
        // Extractor: StepCip<Reading> → Map<String, Object>
        // Takes current measurement (mi+1) values
        this.str = new AutomatonSTR<>(automaton, step -> step.current().getValues());
    }

    @Override
    public List<Configuration> initial() {
        return List.copyOf(str.initial());
    }

    @Override
    public List<Transition> actions(StepCip<Reading> input, Configuration configuration) {
        return List.copyOf(str.actions(input, configuration));
    }

    @Override
    public List<Pair<AutomatonOutput, Configuration>> execute(
            Transition action,
            StepCip<Reading> input,
            Configuration configuration) {

        // Execute via our STR, then convert Pair types
        return str.execute(action, input, configuration).stream()
                .map(p -> new Pair<>(p.output(), p.configuration()))
                .toList();
    }
}