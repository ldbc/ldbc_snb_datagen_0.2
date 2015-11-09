package ldbc.snb.datagen.generator;

import ldbc.snb.datagen.generator.tools.GraphUtils;
import ldbc.snb.datagen.generator.tools.PersonGraph;
import ldbc.snb.datagen.objects.Knows;
import ldbc.snb.datagen.objects.Person;
import org.apache.hadoop.conf.Configuration;

import java.util.*;

/**
 * Created by aprat on 11/15/14.
 */
public class ClusteringKnowsGenerator implements KnowsGenerator {

    Random rand;
    private ArrayList<Float> percentages = null;
    private int stepIndex = 0;
    private float targetCC = 0.0f;
    private int numMisses = 0;
    private int numCoreCoreEdges = 0;
    private int numCorePeripheryEdges = 0;
    private int numCoreExternalEdges = 0;
    private float min_community_prob_ = 0.0f;

    private class PersonInfo {
        public int index_;
        public long degree_;
        public long original_degree_;
    }

    private class Community {
        public long id_;
        public ArrayList<PersonInfo> core_;
        public ArrayList<PersonInfo> periphery_;
        public float p_ = 1.0f;
    }


    private class PersonInfoComparator implements Comparator<PersonInfo>{
        public int compare(PersonInfo a, PersonInfo b) {
            if( a.degree_ != b.degree_ )
                return (int)(b.degree_ - a.degree_ );
            return a.index_ - b.index_;
        }
    }

    private class ClusteringInfo {
        public ArrayList<Boolean> is_core_ = new ArrayList<Boolean>();
        public ArrayList<Double> core_node_expected_core_degree_ = new ArrayList<Double>();
        public ArrayList<Double> core_node_excedence_degree_ = new ArrayList<Double>();
        public ArrayList<Double> core_node_expected_periphery_degree_ = new ArrayList<Double>();
        public ArrayList<Double> core_node_expected_external_degree_ = new ArrayList<Double>();
        public ArrayList<Double> clustering_coefficient_ = new ArrayList<Double>();
        public ArrayList<Long> community_core_stubs_ = new ArrayList<Long>();
        public ArrayList<Float> community_core_probs_ = new ArrayList<Float>();
        public ArrayList<Integer> core_nodes_ = new ArrayList<Integer>();
        public ArrayList<Integer> community_id_ = new ArrayList<Integer>();
        public float sumProbs = 0.0f;
        public int numCommunities = 0;

        ClusteringInfo( int size, ArrayList<Community> communities ) {
            for( int i = 0; i < size; ++i) {
                core_node_expected_core_degree_.add(0.0);
                core_node_excedence_degree_.add(0.0);
                core_node_expected_periphery_degree_.add(0.0);
                core_node_expected_external_degree_.add(0.0);
                is_core_.add(false);
                clustering_coefficient_.add(0.0);
                community_id_.add(0);
            }
            for( int i = 0; i < communities.size(); ++i) {
                community_core_stubs_.add(0L);
                community_core_probs_.add(0.0f);
            }

            int index = 0;
            for( Community c: communities) {
                for( PersonInfo pI : c.core_) {
                    core_nodes_.add(pI.index_);
                    is_core_.set(pI.index_, true);
                    community_id_.set(pI.index_,index );
                }

                for( PersonInfo pI : c.periphery_) {
                    is_core_.set(pI.index_, false);
                    community_id_.set(pI.index_,index );
                }
                index++;
            }

            numCommunities = communities.size();
            sumProbs = communities.size();
        }
    }


    public ClusteringKnowsGenerator() {
        rand = new Random();
    }

    private Community findSolution( ArrayList<Person> persons, int begin, int last) {
        ArrayList<PersonInfo> nodes = new ArrayList<PersonInfo>();
        for (int i = begin; i < last + 1; ++i ) {
            Person p = persons.get(i);
            PersonInfo pInfo = new PersonInfo();
            pInfo.index_ = i;
            pInfo.degree_ = Knows.target_edges(p,percentages,stepIndex);
            pInfo.original_degree_ = (long)(p.maxNumKnows());
            nodes.add(pInfo);
        }

        Collections.sort(nodes, new PersonInfoComparator() );
        ArrayList<PersonInfo> core = new ArrayList<PersonInfo>();
        ArrayList<PersonInfo> periphery = new ArrayList<PersonInfo>();
        for (PersonInfo pI : nodes ) {
            if(pI.degree_ >= core.size() ) {
                core.add(pI);
            } else {
                periphery.add(pI);
            }
        }
        return checkBudget(persons, core, periphery);
    }

    private ArrayList<Long> createInitialBudget( ArrayList<PersonInfo> core) {
        return createInitialBudget(core, 1.0f);
    }

    private ArrayList<Long> createInitialBudget( ArrayList<PersonInfo> core, float p ) {
        ArrayList<Long> budget = new ArrayList<Long>();
        int coreSize = core.size();
        for ( PersonInfo pI : core ) {
            budget.add(pI.degree_ - (long)((coreSize - 1)*p));
        }
        return budget;
    }

    private Community checkBudget(ArrayList<Person> persons, ArrayList<PersonInfo> core, ArrayList<PersonInfo> periphery) {
        ArrayList<Long> temp_budget = createInitialBudget(core);
        Collections.sort(periphery, new PersonInfoComparator());
        for(PersonInfo pI : periphery ) {
            long degree = pI.degree_;
            long remaining = degree;
            int i = 0;
            while(i < temp_budget.size() && remaining > 0) {
                if( temp_budget.get(i) > 0) {
                    temp_budget.set(i, temp_budget.get(i) - 1);
                    remaining -= 1;
                }
                ++i;
            }
            if (remaining > 0) {
                return null;
            }
        }
        Community community = new Community();
        community.core_ = core;
        community.periphery_ = periphery;
        return community;
    }

    private void testCommunity(Community c) {
        for(PersonInfo pI : c.core_ ) {
            if(pI.degree_ < (c.core_.size() - 1))  System.out.println("Error in building communities\n");
        }
    }

    private  ArrayList<Community> generateCommunities( ArrayList<Person> persons) {
        ArrayList<Community> communities = new ArrayList<Community>();
        int last = 0;
        int begin = 0;
        int end = persons.size();
        int threshold = 5;
        while (last < end ) {
            int best = last;
            int numTries = 0;
            Community bestCommunity = null;
            while( numTries <= threshold && last < end ) {
                numTries++;
                Community community = findSolution(persons, begin, last);
                if( community != null ) {
                    bestCommunity = community;
                    numTries = 0;
                    best=last;
                }
                last++;
            }
            bestCommunity.id_ = communities.size();
            communities.add(bestCommunity);
            testCommunity(bestCommunity);

            last = best + 1;
            begin = last;
        }
        return communities;
    }

    private void computeCommunityInfo(ClusteringInfo cInfo, Community c, float prob) {
        long [] peripheryBudget = new long[c.periphery_.size()];
        Collections.sort(c.periphery_, new PersonInfoComparator());
        int index = 0;
        for (PersonInfo pI : c.periphery_) {
            peripheryBudget[index] = pI.degree_;
            index++;
        }

        // Initializing cInfo with expected degrees
        for (PersonInfo pI : c.core_) {
            cInfo.core_node_expected_core_degree_.set(pI.index_,(c.core_.size() - 1) * (double)prob);
            cInfo.core_node_excedence_degree_.set(pI.index_, pI.degree_ - cInfo.core_node_expected_core_degree_.get(pI.index_));
            cInfo.core_node_expected_periphery_degree_.set(pI.index_, 0.0);
        }

        long remainingStubs = 0;
        for (PersonInfo pI : c.core_) {
            double pDegree = 0;
            double maxDegree = (cInfo.core_node_excedence_degree_.get(pI.index_));
            for(index = 0; index < peripheryBudget.length; ++index) {
                if (peripheryBudget[index] != 0 && pDegree < maxDegree) {
                    pDegree++;
                    peripheryBudget[index]--;
                }
            }

            cInfo.core_node_expected_periphery_degree_.set(pI.index_, pDegree);

            double deg = ((pI.degree_ - cInfo.core_node_expected_core_degree_.get(pI.index_) - cInfo.core_node_expected_periphery_degree_.get(pI.index_)));
            cInfo.core_node_expected_external_degree_.set(pI.index_, deg);
            remainingStubs += deg;
        }
        cInfo.community_core_stubs_.set((int)c.id_, remainingStubs);
        cInfo.community_core_probs_.set((int)c.id_, c.p_);
    }


    private void estimateCCCommunity( ClusteringInfo cInfo, Community c, float prob ) {
        computeCommunityInfo(cInfo, c, prob);

        float probSameCommunity = 0.0f;
        float probTriangleSameCommunity = 0.0f;
        long sumStubs = 0;
        int index = 0;
        for(Long l : cInfo.community_core_stubs_) {
            if(index != c.id_) {
                float p = l*l;
                probSameCommunity += p;
                probTriangleSameCommunity += p*cInfo.community_core_probs_.get(index);
                sumStubs+=l;
            }
            index++;
        }
        probSameCommunity /= (sumStubs*sumStubs);
        probTriangleSameCommunity /= (sumStubs*sumStubs);

        float probTwoConnected  = 0.0f;
        for( Integer i : cInfo.core_nodes_ ) {
            double degree1 = cInfo.core_node_expected_external_degree_.get(i);
            if(degree1 >= 1) {
                for (Integer ii : cInfo.core_nodes_) {
                    if(cInfo.community_id_.get(i) != cInfo.community_id_.get(i)) {
                        double degree2 = cInfo.core_node_expected_external_degree_.get(ii);
                        if (degree2 >= 1)
                            probTwoConnected += degree1 * degree2 / (float) (2 * sumStubs * sumStubs);
                    }
                }
            }
        }

        // Computing clustering coefficient of periphery nodes
        for (PersonInfo pI: c.periphery_) {
            if(pI.degree_ > 1) {
                cInfo.clustering_coefficient_.set(pI.index_, (double)pI.degree_*(pI.degree_-1)*prob/(pI.original_degree_*(pI.original_degree_-1)));
                //cInfo.clustering_coefficient_.set(pI.index_, (double)prob);
                //cInfo.clustering_coefficient_.set(pI.index_, 0.0);
            }
        }

        long [] peripheryBudget = new long[c.periphery_.size()];
        index = 0;
        for(PersonInfo pI: c.periphery_) {
            peripheryBudget[index] = pI.degree_;
            index++;
        }

        // Computing clustering coefficient of core nodes
        for ( PersonInfo pI : c.core_ ){
            int size = c.core_.size();
            if( pI.degree_ > 1 ) {
                // core core triangles
                double internalTriangles = 0.0;
                double internalDegree = cInfo.core_node_expected_core_degree_.get(pI.index_);

                if(internalDegree >= 2.0) {
                    internalTriangles = (internalDegree * (internalDegree - 1) * prob);
                }
                

                // core periphery triangles
                double peripheryTriangles = 0;
                long remainingDegree = pI.degree_;
                for(index = 0; index < peripheryBudget.length; ++index) {
                    if(peripheryBudget[index] > 0) {
                        peripheryBudget[index]--;
                        remainingDegree--;
                        if(c.periphery_.get(index).degree_ > 1) {
                            peripheryTriangles += 2*(c.periphery_.get(index).degree_ - 1) * prob;
                        }
                    }
                    if(remainingDegree == 0) break;
                }

                double external_triangles = 0.0;
                if(cInfo.core_node_expected_external_degree_.get(pI.index_) >= 2.0) {
                    external_triangles += cInfo.core_node_expected_external_degree_.get(pI.index_) * (cInfo.core_node_expected_external_degree_.get(pI.index_) - 1) * probTriangleSameCommunity;
                    external_triangles += cInfo.core_node_expected_external_degree_.get(pI.index_) * (cInfo.core_node_expected_external_degree_.get(pI.index_) - 1) * (1 - probSameCommunity) * probTwoConnected;
                }


                //double degree = finalInternalDegree;
                /*double degree = (cInfo.core_node_expected_core_degree_.get(pI.index_) +
                        cInfo.core_node_expected_periphery_degree_.get(pI.index_) +
                        cInfo.core_node_expected_external_degree_.get(pI.index_));*/

                double degree = pI.original_degree_;

                //System.out.println("Internal Triangles: "+internalTriangles+" , degree: "+degree);
                if( degree >= 2.0 ) {
                    cInfo.clustering_coefficient_.set(pI.index_, (internalTriangles+peripheryTriangles+external_triangles)/(degree*(degree-1)));
                }
            }
        }
    }

    float clusteringCoefficient(ArrayList<Community> communities, ClusteringInfo cInfo ) {
        float CC =  clusteringCoefficient(communities, cInfo,true);
        return CC;
    }

    float clusteringCoefficient( ArrayList<Community> communities, ClusteringInfo cInfo, Boolean countZeros ) {
        float accum = 0.0f;
        int count = 0;
        for (Community c : communities) {
            for(PersonInfo pI : c.core_) {
                if(pI.degree_ > 0) {
                    accum += cInfo.clustering_coefficient_.get(pI.index_);
                    count++;
                }
            }

            for(PersonInfo pI : c.periphery_) {
                if(pI.degree_ > 0) {
                    accum += cInfo.clustering_coefficient_.get(pI.index_);
                    count++;
                }
            }
        }
        if(countZeros) {
            return accum / (float) cInfo.clustering_coefficient_.size();
        }
        return accum / (float) count;
    }

    void refineCommunities( ClusteringInfo cInfo, ArrayList<Community> communities, float targetCC ) {
        float currentCC = clusteringCoefficient(communities, cInfo);
        int lookAhead = 5;
        int tries = 0;
        while( Math.abs(currentCC - targetCC)  > 0.001 && tries <= lookAhead) {
         //   System.out.println(currentCC);
            boolean found = false;
            tries+=1;
            if( currentCC < targetCC ) {
                found = improveCC(cInfo, communities);
            } else if( currentCC > targetCC){
                found = worsenCC(cInfo, communities);
            }
            if( found ) {
               currentCC = clusteringCoefficient(communities, cInfo);
               tries = 0;
            }
        }
        System.out.println("Clustering Coefficient after refinement: " + currentCC);
    }

    float step(int n) {
        return 3.0f/(float)n;
    }

    boolean improveCC(ClusteringInfo cInfo, ArrayList<Community> communities) {
        ArrayList<Community>  filtered = new ArrayList<Community>();
        for(Community c : communities ) {
            if(c.p_ < 1.0f ) filtered.add(c);
        }
        if(filtered.size() == 0) return false;
        int index = rand.nextInt(filtered.size());
        Community c = filtered.get(index);
        float step = step(c.core_.size());
        c.p_ = c.p_ + step > 1.0f ? 1.0f : c.p_ + step;
        cInfo.sumProbs+=0.01;
        estimateCCCommunity(cInfo, c, c.p_);
        return true;
    }

    boolean worsenCC(ClusteringInfo cInfo, ArrayList<Community> communities) {
        ArrayList<Community>  filtered = new ArrayList<Community>();
        for(Community c : communities ) {
            if(c.p_ > min_community_prob_ ) filtered.add(c);
        }
        if(filtered.size() == 0) return false;
        int index = rand.nextInt(filtered.size());
        Community c = filtered.get(index);
        float step = step(c.core_.size());
        c.p_ = c.p_ - step < min_community_prob_ ? min_community_prob_ : c.p_ - step ;
        cInfo.sumProbs-=0.01;
        estimateCCCommunity(cInfo, c, c.p_ );
        return true;
    }

    void createEdgesCommunityCore(ArrayList<Person> persons, Community c) {
        for ( PersonInfo pI : c.core_) {
            for( PersonInfo other: c.core_) {
                if(pI.index_ < other.index_ ) {
                    float prob = rand.nextFloat();
                    if( prob <= c.p_ ) {
                        // crear aresta
                        if(Knows.createKnow(rand, persons.get(pI.index_), persons.get(other.index_)))
                            numCoreCoreEdges++;
                        else
                            numMisses++;
                    }
                }
            }
        }
    }

    void createEdgesCommunityPeriphery(ClusteringInfo cInfo, ArrayList<Person> persons, Community c) {

        //long start = System.currentTimeMillis();
        long [] peripheryBudget = new long[c.periphery_.size()];
        int index = 0;
        for(PersonInfo pI: c.periphery_) {
            peripheryBudget[index] = pI.degree_;
            ++index;
        }

        for ( PersonInfo pI : c.core_ ) {
            double pDegree = 0;
            double maxDegree = cInfo.core_node_expected_periphery_degree_.get(pI.index_);
            for (index = 0; index < peripheryBudget.length;  ++index ) {
                if( peripheryBudget[index] != 0 && pDegree < maxDegree)  {
                    pDegree++;
                    peripheryBudget[index]--;
                    if(Knows.createKnow(rand, persons.get(pI.index_), persons.get(c.periphery_.get(index).index_)))
                        numCorePeripheryEdges++;
                    else
                        numMisses++;
                }
            }
        }

        for( PersonInfo pI : c.periphery_ ) {
            if(persons.get(pI.index_).knows().size() > pI.degree_ ) {
                System.out.println("ERROR");
            }
        }
        //long end = System.currentTimeMillis();
        //System.out.println("Time to create core-periphery edges: "+(end-start));
    }

    void fillGraphWithRemainingEdges(ClusteringInfo cInfo, ArrayList<Community> communities, ArrayList<Person> persons) {
        ArrayList<PersonInfo> stubs = new ArrayList<PersonInfo> ();
        LinkedList<Integer> indexes = new LinkedList<Integer>();
        Integer ii = 0;
        for ( Community c : communities ) {
            for (PersonInfo pI : c.core_ ) {
                long diff = pI.degree_ - persons.get(pI.index_).knows().size();
                if( diff > 0 ) {
                    for( int i = 0; i < diff; ++i) {
                       stubs.add(pI);
                        indexes.add(ii++);
                    }
                }
            }
        }

        Collections.shuffle(stubs,rand);
        Collections.shuffle(indexes,rand);

        while(indexes.size()>0) {
            int index = indexes.pop();
            PersonInfo first = stubs.get(index);
            if(indexes.size() > 0) {
                int index2 = indexes.pop();
                PersonInfo second = stubs.get(index2);
                // create edge
                if(persons.get(first.index_) == persons.get(second.index_)) {
                    numMisses++;
                    continue;
                }
                if(Knows.createKnow(rand, persons.get(first.index_), persons.get(second.index_)))
                    numCoreExternalEdges++;
                else
                    numMisses++;
            }
        }
    }


    public void generateKnows( ArrayList<Person> persons, int seed, ArrayList<Float> percentages, int step_index )  {

        long start, end;
        rand.setSeed(seed);
        this.percentages = percentages;
        this.stepIndex = step_index;

        start = System.currentTimeMillis();
        ArrayList<Community>  communities = generateCommunities(persons);
        end = System.currentTimeMillis();
        System.out.println("Time to configure communities: "+(end-start));

        ClusteringInfo cInfo = new ClusteringInfo( persons.size(), communities );

        System.out.println("Number of generated communities: "+communities.size());


        start = System.currentTimeMillis();
        for( Community c : communities ) {
            c.p_ = 1.0f;
            computeCommunityInfo(cInfo, c, 1.0f);
        }
        end = System.currentTimeMillis();
        System.out.println("Time to compute initial community information: "+(end-start));

        start = System.currentTimeMillis();
        for( Community c : communities ) {
            c.p_ = 1.0f;
            estimateCCCommunity(cInfo, c, c.p_);
        }

        float maxCC = clusteringCoefficient(communities, cInfo);
        end = System.currentTimeMillis();
        System.out.println("maxCC: "+maxCC);
        System.out.println("Time to compute maximum CC: "+(end-start));

        start = System.currentTimeMillis();
        for( Community c : communities ) {
            c.p_ = 0.5f;//rand.nextFloat();
            //c.p_ = rand.nextFloat();
            estimateCCCommunity(cInfo, c, c.p_ );
        }
        end = System.currentTimeMillis();
        System.out.println("Time to compute the initial solution: "+(end-start));

        PersonGraph graph;
        boolean iterate;
        float fakeTargetCC = targetCC;
        int numIterations = 0;
        do {
            System.out.println("Starting refinement iteration");
            iterate = false;
            start = System.currentTimeMillis();
            refineCommunities(cInfo, communities, fakeTargetCC);
            end = System.currentTimeMillis();
            System.out.println("Time to refine communities: "+(end-start));

            System.out.println("Creating graph");

            start = System.currentTimeMillis();
            for(Community c : communities ) {
                createEdgesCommunityCore(persons, c);
                createEdgesCommunityPeriphery(cInfo, persons, c);
            }
            fillGraphWithRemainingEdges(cInfo, communities, persons);
            end = System.currentTimeMillis();
            System.out.println("Time to generate graph: "+(end-start));

            graph = new PersonGraph(persons);
            System.out.println("Computing clustering coefficient");
            double finalCC = 0;
            ArrayList<Double> clusteringCoefficient = GraphUtils.ClusteringCoefficientList(graph);
            int i = 0;
            for( Person p : persons) {
                long degree = graph.neighbors(p.accountId()).size();
                long originalDegree = p.maxNumKnows();
                if(originalDegree > 1)
                    finalCC += clusteringCoefficient.get(i) * degree*(degree - 1) / (originalDegree*(originalDegree-1));
                i++;
            }
            finalCC /= persons.size();
            //double finalCC = GraphUtils.ClusteringCoefficient(graph);

            System.out.println("Clustering coefficient of the generated graph: "+finalCC);
            double delta = targetCC - finalCC;
            if( Math.abs( delta ) > 0.001 ) {
                resetStatistics();
                for(Person person: persons) {
                    person.knows().clear();
                }
                fakeTargetCC +=  delta*0.8f;
                System.out.println("New Fake targetCC: "+fakeTargetCC );
                iterate = true;
            }
            numIterations++;
        }while( iterate );

        int countMore = 0;
        int countLess = 0;
        int sumMore = 0;
        int sumLess = 0;
        int index = 0;
        int countDegreeZero = 0;
        for( Person p : persons ) {
            if(cInfo.is_core_.get(index)) {
                long target = Knows.target_edges(p, percentages, step_index);
                if (p.knows().size() > target) {
                    sumMore += -target + p.knows().size();
                    countMore++;
                } else if (p.knows().size() < target) {
                    //System.out.println(p.knows().size()+" "+target);
                    sumLess += target - p.knows().size();
                    countLess++;
                }
            }
            if(p.knows().size() == 0) countDegreeZero++;
            ++index;
        }

        System.out.println("Number of iterations to converge: "+numIterations);
        System.out.println("Number of persons with more degree than expected: "+countMore);
        System.out.println("Sum of excess degree: "+sumMore);
        System.out.println("Number of persons with less degree than expected: "+countLess);
        System.out.println("Sum of degree missed: "+sumLess);
        System.out.println("Number of persons with degree zero: "+countDegreeZero);
        printStatistics();
    }

    public void initialize( Configuration conf ) {
        targetCC = conf.getFloat("ldbc.snb.datagen.generator.ClusteringKnowsGenerator.clusteringCoefficient", 0.1f);
        System.out.println("Initialized clustering coefficient to "+targetCC);
        targetCC /= 2.0f;
    }

    public void resetStatistics() {
        numCoreCoreEdges = 0;
        numCorePeripheryEdges = 0;
        numCoreExternalEdges = 0;
        numMisses = 0;
    }

    public void printStatistics() {
        System.out.println("Number core-core edges: "+numCoreCoreEdges);
        System.out.println("Number core-periphery edges: "+numCorePeripheryEdges);
        System.out.println("Number core-external edges: "+numCoreExternalEdges);
        System.out.println("Number edges missed: "+numMisses);
    }
}
