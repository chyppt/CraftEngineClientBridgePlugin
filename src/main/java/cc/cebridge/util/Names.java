package cc.cebridge.util;

import net.kyori.adventure.text.Component;

public final class Names {

    private Names() {
    }

    /** ruby_ore -> "Ruby Ore" */
    public static Component prettify(String id) {
        String[] parts = id.split("[_\\- ]+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) sb.append(part.substring(1));
            sb.append(' ');
        }
        return Component.text(sb.toString().trim());
    }
}
