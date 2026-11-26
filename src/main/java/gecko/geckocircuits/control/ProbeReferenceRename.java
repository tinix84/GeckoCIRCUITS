package gecko.geckocircuits.control;

import gecko.core.simulation.ProbeReference;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record ProbeReferenceRename(List<Change> changes) {
    private record Change(int index, String before, String after) { }

    ProbeReferenceRename {
        changes = List.copyOf(changes);
    }

    static ProbeReferenceRename capture(String[] entries, String previous, String current) {
        Pattern target = Pattern.compile("^(\\s*)" + Pattern.quote(previous) + "(?=\\s*\\.(?:i|v|state)\\s*$)");
        String replacement = "$1" + Matcher.quoteReplacement(current);
        var changes = new ArrayList<Change>();
        for (int index = 0; index < entries.length; index++) {
            String entry = entries[index];
            String renamed = target.matcher(entry).replaceFirst(replacement);
            if (!entry.equals(renamed)) {
                changes.add(new Change(index, entry, renamed));
            }
        }
        return new ProbeReferenceRename(changes);
    }

    boolean apply(String[] entries, boolean rename) {
        boolean changed = false;
        for (var change : changes) {
            int index = change.index();
            String before = (rename ? change.before() : change.after()).strip();
            if (index < entries.length && entries[index].strip().equals(before)) {
                String after = (rename ? change.after() : change.before()).strip();
                entries[index] = entries[index].replace(before, after);
                changed = true;
            }
        }
        return changed;
    }

    ProbeReferenceRename escapedForEditor() {
        return new ProbeReferenceRename(changes.stream().map(change -> new Change(change.index(),
                ProbeReference.escapeText(change.before()), ProbeReference.escapeText(change.after()))).toList());
    }

    boolean isEmpty() {
        return changes.isEmpty();
    }
}
