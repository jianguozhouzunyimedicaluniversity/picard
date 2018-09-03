package picard.illumina;

import htsjdk.samtools.util.CollectionUtil;
import htsjdk.samtools.util.Log;
import htsjdk.samtools.util.SequenceUtil;
import org.apache.commons.lang3.StringUtils;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.barclay.help.DocumentedFeature;
import picard.cmdline.CommandLineProgram;
import picard.cmdline.StandardOptionDefinitions;
import picard.cmdline.programgroups.OtherProgramGroup;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Created by farjoun on 8/21/18.
 */
@CommandLineProgramProperties(
        summary = "blah",
        oneLineSummary = "blah",
        programGroup = OtherProgramGroup.class
)
@DocumentedFeature
public class SelectBarcodes extends CommandLineProgram {

    @Argument
    public List<File> BARCODES_MUST_HAVE;

    @Argument
    public List<File> BARCODES_CHOOSE_FROM;

    @Argument(shortName = StandardOptionDefinitions.OUTPUT_SHORT_NAME)
    public File OUTPUT;

    private static final int FAR_ENOUGH = 3;

    static final List<String> mustHaveBarcodes = new ArrayList<>();
    static final List<String> barcodes = new ArrayList<>();

    private final List<BitSet> adjacencyMatrix = new ArrayList<>();

    private int mustHaves = 0;

    static File output = null;
    private static Log LOG = Log.getInstance(SelectBarcodes.class);

    static Map<Integer, BitSet> Ps = new CollectionUtil.DefaultingMap<>((i) -> new BitSet(), true);
    static Map<Integer, BitSet> Xs = new CollectionUtil.DefaultingMap<>((i) -> new BitSet(), true);
    static Map<Integer, BitSet> Rs = new CollectionUtil.DefaultingMap<>((i) -> new BitSet(), true);
    static Map<Integer, BitSet> Diffs = new HashMap<>();
    static int recursionLevel;

    @Override
    public int doWork() {
        output = OUTPUT;

        //open files make lists of barcodes
        openBarcodes();
        LOG.info("Opened Barcode files.");
        // calculate distance matrix and ajacency matrix
        calculateAjacencyMatrix();
        LOG.info("Calculated distances");

        LOG.info("there are " + mustHaveBarcodes.size() + " MUST_HAVE barcodes.");
        LOG.info("there are " + barcodes.size() + " other barcodes to choose from (after possibly rejecting some).");

        try (final PrintWriter writer = new PrintWriter("all.barcodes.txt")) {
            mustHaveBarcodes.forEach(writer::println);
            barcodes.forEach(writer::println);

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        // call BronKerbosch2 saving best selections to disk.
        find_cliques(adjacencyMatrix);

        return 0;
    }

    private void calculateAjacencyMatrix() {

        final List<String> filteredBarcodes = barcodes.stream().filter(b -> {
                    if (mustHaveBarcodes.stream().anyMatch(m -> !areFarEnoughHamming(b, m))) {
                        LOG.info(String.format("rejecting barcode: %s, it's too close to a MUST_HAVE barcode.",
                                b));
                        return false;
                    }
                    return true;
                }
        ).collect(Collectors.toList());

        barcodes.clear();
        barcodes.addAll(filteredBarcodes);

        try (final PrintWriter writer = new PrintWriter("distances.txt")) {

            for (int ii = 0; ii < barcodes.size(); ii++) {
                final BitSet ajacency = new BitSet(barcodes.size());

                for (int jj = 0; jj < barcodes.size(); jj++) {
                    ajacency.set(jj, areFarEnoughLevenshtein(barcodes.get(ii), barcodes.get(jj)));
                    writer.append(ajacency.get(jj) ? "1" : "0").append(String.valueOf('\t'));
                }
                writer.append('\n');
                adjacencyMatrix.add(ii, ajacency);
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }

    static int hamming(final String lhs, final String rhs) {
        return Math.min(picard.util.SequenceUtil.calculateEditDistance(lhs, rhs),
                picard.util.SequenceUtil.calculateEditDistance(lhs, SequenceUtil.reverseComplement(rhs)));

    }

    static int levenshtein(final String lhs, final String rhs) {
        return Math.min(StringUtils.getLevenshteinDistance(lhs, rhs),
                StringUtils.getLevenshteinDistance(lhs, SequenceUtil.reverseComplement(rhs)));

    }

    private boolean areFarEnoughHamming(final String lhs, final String rhs) {

        return hamming(lhs, rhs) >= FAR_ENOUGH;
    }

    private boolean areFarEnoughLevenshtein(final String lhs, final String rhs) {

        return levenshtein(lhs, rhs) >= FAR_ENOUGH;
    }

    private void openBarcodes() {
        barcodes.clear();

        BARCODES_MUST_HAVE.forEach(b->readBarcodesFile(b,mustHaveBarcodes));

        mustHaves = mustHaveBarcodes.size();

        BARCODES_CHOOSE_FROM.forEach(b->readBarcodesFile(b,barcodes));
    }

    private void readBarcodesFile(final File f, final List<String> addTo) {
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().equals("")) continue;
                if (!addTo.contains(line) && !addTo.contains(SequenceUtil.reverseComplement(line))) {
                    addTo.add(line);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static void find_cliques(List<BitSet> graph) {

        final Integer[] degeneracyOrder = getDegeneracyOrder(graph);
        recursionLevel = 0;

        BitSet r = Rs.get(recursionLevel);

        BitSet p = Ps.get(recursionLevel);
        p.set(0, barcodes.size(), true);
        p.andNot(r);

        BitSet x = Xs.get(recursionLevel);
        x.clear();
        final BitSet best_clique = new BitSet();

        for (final Integer v : degeneracyOrder) {
            recursionLevel = 0;

            p = Ps.get(recursionLevel);
            r = Rs.get(recursionLevel);
            x = Xs.get(recursionLevel);

            if (r.get(v)) continue;

            r.clear();
            r.set(v);
            LOG.info("examining node " + v);
            final BitSet neighs = graph.get(v);

            p.clear();
            p.or(neighs);

            x.clear();

            while (recursionLevel >= 0) {

                p = Ps.get(recursionLevel);
                r = Rs.get(recursionLevel);
                x = Xs.get(recursionLevel);

                //  LOG.info(String.format("in while (%d) %s %s %s (%s)", recursionLevel, r, p, x, best_clique));
                if (p.isEmpty() && x.isEmpty()) {

                    registerClique(r, best_clique);
                    recursionLevel--;

                    continue;
                }
                if (!Diffs.containsKey(recursionLevel)) {
                    final BitSet finalP = p;

                    final int u = Stream.concat(p.stream().boxed(), x.stream().boxed())
                            .max(Comparator.comparingInt(o -> intersection(finalP, graph.get(o)).cardinality()))
                            .orElse(-1); //should never happen

//                    final int u = IntStream.concat(p.stream(), x.stream()).findFirst().orElse(-1); //should never happen
//                    LOG.info("examining pivot " + u);
                    Diffs.put(recursionLevel, difference(p, graph.get(u)));
                }
                final int vv = Diffs.get(recursionLevel).nextSetBit(0);
                if (vv == -1) {
                    Diffs.remove(recursionLevel);
                    p.clear();
                    x.clear();
                    r.clear();

                    recursionLevel--;
                    continue;
                }
                Diffs.get(recursionLevel).clear(vv);

                final BitSet recNeighs = graph.get(vv);

                Rs.get(recursionLevel + 1).clear();
                Rs.get(recursionLevel + 1).or(r);
                Rs.get(recursionLevel + 1).set(vv);

                Xs.get(recursionLevel + 1).clear();
                Xs.get(recursionLevel + 1).or(x);
                Xs.get(recursionLevel + 1).and(recNeighs);

                Ps.get(recursionLevel + 1).clear();
                Ps.get(recursionLevel + 1).or(p);
                Ps.get(recursionLevel + 1).and(recNeighs);

                p.clear(vv);
                x.set(vv);

                recursionLevel++;

            }

            p.clear(v);
            x.set(v);
        }
    }

    private static void registerClique(final BitSet r, final BitSet bestClique) {
        if (r.cardinality() > bestClique.cardinality()) {
            bestClique.clear();
            bestClique.or(r);
            System.out.println("best.cardinality()=" + (bestClique.cardinality() + mustHaveBarcodes.size()));

            output.delete();
            try (PrintWriter writer = new PrintWriter(output)) {

                mustHaveBarcodes.stream().forEach(b -> writer.println(-1 + "\t" + b));
                bestClique.stream().forEach(i -> writer.println(Integer.toString(i) + "\t" + barcodes.get(i)));
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }
    }

//    static BitSet union(final BitSet set, final int node) {
//        BitSet ret = BitSet.valueOf(set.toLongArray());
//        ret.set(node);
//        return ret;
//    }

    static BitSet union(final BitSet lhs, final BitSet rhs) {
        BitSet ret = BitSet.valueOf(lhs.toLongArray());
        ret.or(rhs);
        return ret;
    }

    static BitSet intersection(final BitSet lhs, final BitSet rhs) {
        BitSet ret = BitSet.valueOf(lhs.toLongArray());
        ret.and(rhs);
        return ret;
    }

    static BitSet difference(final BitSet lhs, final BitSet rhs) {
        BitSet ret = BitSet.valueOf(lhs.toLongArray());
        ret.andNot(rhs);
        return ret;
    }

    private static Integer[] getDegeneracyOrder(final List<BitSet> graph) {
        List<Integer> ordering = new ArrayList<>();
        Set<Integer> ordering_set = new HashSet<>();

        // a map from the vertices to their cardinality
        Map<Integer, Integer> degrees = new CollectionUtil.DefaultingMap<>(0);

        // a map form a given degeneracy to the list of vertices with that degeneracy
        @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
        Map<Integer, List<Integer>> degen = new CollectionUtil.DefaultingMap<>(i -> new ArrayList<>(), true);
        int max_deg = -1;
        for (int v = 0; v < graph.size(); v++) {
            int deg = graph.get(v).cardinality();
            degen.get(deg).add(v);
            degrees.put(v, deg);
            if (deg > max_deg) {
                max_deg = deg;
            }
        }
        outter:
        while (true) {
            int i = 0;
            while (true) {
                if (i <= max_deg) {
                    if (degen.get(i).size() != 0) {
                        break;
                    }
                    i += 1;

                } else {
                    break outter;
                }
            }

            final Integer v = degen.get(i).remove(degen.get(i).size() - 1);

            ordering.add(v);
            ordering_set.add(v);
            graph.get(v).stream().forEach(w -> {

                if (!ordering_set.contains(w)) {
                    final int deg = degrees.get(w);
                    degen.get(deg).remove(Integer.valueOf(w));
                    if (deg > 0) {
                        degrees.put(w, degrees.get(w) - 1);
                        degen.get(deg - 1).add(w);
                    }
                }
            });
        }
        Collections.reverse(ordering);
        return ordering.toArray(new Integer[0]);
    }
}