/*  This file is part of GeckoCIRCUITS. Copyright (C) ETH Zurich, Gecko-Simulations AG
 *
 *  GeckoCIRCUITS is free software: you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation, either version 3 of the License, or (at your option) any later version.
 *
 *  GeckoCIRCUITS is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 *  PURPOSE.  See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  GeckoCIRCUITS.  If not, see <http://www.gnu.org/licenses/>.
 */
package gecko.core.io.ltspice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Intermediate representation of a complete LTspice circuit parsed from a .asc file.
 * Contains all components, wires, flags, and simulation parameters.
 */
public class LtspiceCircuit {

    private final List<LtspiceComponent> components = new ArrayList<>();
    private final List<LtspiceWire> wires = new ArrayList<>();
    private final List<LtspiceFlag> flags = new ArrayList<>();
    private int sheetWidth;
    private int sheetHeight;
    private int version;

    /** Source filename for diagnostic messages */
    private String sourceName;

    public void addComponent(LtspiceComponent component) {
        components.add(component);
    }

    public void addWire(LtspiceWire wire) {
        wires.add(wire);
    }

    public void addFlag(LtspiceFlag flag) {
        flags.add(flag);
    }

    public List<LtspiceComponent> getComponents() {
        return Collections.unmodifiableList(components);
    }

    public List<LtspiceWire> getWires() {
        return Collections.unmodifiableList(wires);
    }

    public List<LtspiceFlag> getFlags() {
        return Collections.unmodifiableList(flags);
    }

    public int getSheetWidth() { return sheetWidth; }
    public void setSheetWidth(int sheetWidth) { this.sheetWidth = sheetWidth; }

    public int getSheetHeight() { return sheetHeight; }
    public void setSheetHeight(int sheetHeight) { this.sheetHeight = sheetHeight; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }

    @Override
    public String toString() {
        return "LtspiceCircuit{components=" + components.size()
                + ", wires=" + wires.size()
                + ", flags=" + flags.size() + "}";
    }
}
