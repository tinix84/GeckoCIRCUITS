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
package gecko.examples;

import gecko.core.io.IpesFileWriter;
import gecko.core.io.ltspice.AscToIpesConverter;
import gecko.core.io.ltspice.LtspiceAscParser;
import gecko.core.io.ltspice.LtspiceCircuit;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Command-line tool for converting LTspice .asc schematics to GeckoCIRCUITS .ipes files.
 *
 * <h3>Usage</h3>
 * <pre>
 * java -cp gecko.jar gecko.examples.LtspiceConversionCli &lt;input.asc&gt; [output.ipes]
 * </pre>
 *
 * <p>If {@code output.ipes} is omitted, the output file is placed next to the input
 * file with the {@code .ipes} extension replacing {@code .asc}.</p>
 *
 * <h3>Example</h3>
 * <pre>
 * java -cp gecko.jar gecko.examples.LtspiceConversionCli buck_converter.asc
 * # → produces buck_converter.ipes in the same directory
 *
 * java -cp gecko.jar gecko.examples.LtspiceConversionCli buck_converter.asc /tmp/buck.ipes
 * </pre>
 *
 * <h3>Exit codes</h3>
 * <ul>
 *   <li>0 – conversion successful (warnings may still have been printed)</li>
 *   <li>1 – usage error (wrong arguments)</li>
 *   <li>2 – parse error (invalid .asc file)</li>
 *   <li>3 – I/O error (cannot read input or write output)</li>
 * </ul>
 *
 * <p>Can also be invoked through the GeckoCIRCUITS main JAR:
 * <pre>
 * java -jar GeckoCIRCUITS.jar --convert-ltspice &lt;input.asc&gt; [output.ipes]
 * </pre>
 */
public class LtspiceConversionCli {

    private static final String USAGE =
            "Usage: LtspiceConversionCli <input.asc> [output.ipes]\n" +
            "\n" +
            "  input.asc   – path to the LTspice schematic file\n" +
            "  output.ipes – (optional) path for the generated .ipes file;\n" +
            "                defaults to <input-basename>.ipes in the same directory\n" +
            "\n" +
            "Exit codes: 0=success, 1=usage error, 2=parse error, 3=I/O error";

    /**
     * Converts a single LTspice .asc file to a GeckoCIRCUITS .ipes file.
     *
     * @param args command-line arguments: input.asc [output.ipes]
     */
    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.err.println(USAGE);
            System.exit(1);
        }

        File inputFile = new File(args[0]);
        if (!inputFile.exists()) {
            System.err.println("Error: input file not found: " + inputFile.getAbsolutePath());
            System.exit(3);
        }
        if (!inputFile.getName().toLowerCase().endsWith(".asc")) {
            System.err.println("Warning: input file does not have a .asc extension: " + inputFile.getName());
        }

        File outputFile = args.length == 2 ? new File(args[1]) : deriveOutputFile(inputFile);

        System.out.println("Converting: " + inputFile.getAbsolutePath());
        System.out.println("Output:     " + outputFile.getAbsolutePath());

        // Step 1: parse .asc
        LtspiceAscParser parser = new LtspiceAscParser();
        LtspiceCircuit ltCircuit;
        try {
            ltCircuit = parser.parse(inputFile);
        } catch (LtspiceAscParser.AscParseException e) {
            System.err.println("Parse error: " + e.getMessage());
            System.exit(2);
            return; // required by javac flow analysis (System.exit does not have a void-NoReturn type in Java)
        } catch (IOException e) {
            System.err.println("I/O error reading input: " + e.getMessage());
            System.exit(3);
            return; // required by javac flow analysis (System.exit does not have a void-NoReturn type in Java)
        }

        System.out.println("Parsed: " + ltCircuit.getComponents().size() + " component(s), "
                + ltCircuit.getWires().size() + " wire(s), "
                + ltCircuit.getFlags().size() + " flag(s)");

        // Step 2: convert to CircuitModel
        AscToIpesConverter converter = new AscToIpesConverter();
        AscToIpesConverter.ConversionResult result = converter.convert(ltCircuit);

        System.out.println("Converted: " + result.getModel().getCircuitComponents().size() + " GeckoCIRCUITS component(s)");

        if (!result.getWarnings().isEmpty()) {
            System.out.println("\nWarnings (" + result.getWarnings().size() + "):");
            for (String warning : result.getWarnings()) {
                System.out.println("  [WARN] " + warning);
            }
        }

        // Step 3: write GZIP-compressed .ipes
        IpesFileWriter writer = new IpesFileWriter();
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            byte[] ipesBytes = writer.writeGzipCompressed(result.getModel());
            fos.write(ipesBytes);
        } catch (IOException e) {
            System.err.println("I/O error writing output: " + e.getMessage());
            System.exit(3);
            return; // required by javac flow analysis
        }

        System.out.println("\nDone. Output saved to: " + outputFile.getAbsolutePath());
        System.out.println("Open " + outputFile.getName() + " in GeckoCIRCUITS to view and edit the converted circuit.");
        // Exit with code 0 (success)
    }

    private static File deriveOutputFile(File inputFile) {
        String name = inputFile.getName();
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        File parent = inputFile.getParentFile();
        return parent != null ? new File(parent, base + ".ipes") : new File(base + ".ipes");
    }
}
