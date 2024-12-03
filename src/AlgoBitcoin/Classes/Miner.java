package AlgoBitcoin.Classes;

import AlgoBitcoin.Interfaces.IBlock;
import AlgoBitcoin.Interfaces.IMiner;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

public class Miner implements IMiner {

    public final static int BLOCK_SIZE = 10; // le # de transactions maximum pouvant être stockés dans un seul bloc
    private static int portCounter = 25000;
    private static ArrayList<Miner> allMiners = new ArrayList<>();
    private int id;
    private DatagramSocket socket;
    private int port;
    private ArrayList<IBlock> blockchain = new ArrayList<>();
    private ArrayList<ArrayList<IBlock>> branches = new ArrayList<>();
    private ArrayList<Integer> neighborNodes = new ArrayList<>();
    private volatile List<Transaction> mempool = new ArrayList<>(); // les transactions que nous avons reçu en attente d'être confirmés et insérés dans un bloc
    private ArrayList<Thread> threadConnexions = new ArrayList<>();
    private HashMap<Integer,DatagramPacket> associationTransactionIdAvecInfosClient = new HashMap<>(); // ce dictionnaire associe l'id d'une transaction à les informations nécessaires pour retourner une réponse au client ayant envoyé cette transaction

    public Miner(int id) throws IOException {
        this.id = id;
        this.port = portCounter;
        portCounter += 100;
        allMiners.add(this);

        init(); // retourne le blockchain why?

        Thread threadListener = new Thread(() -> {
            try {
                listenToNetwork();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, "threadMineur" + port + "ListenerAuxRequêtes");
        threadConnexions.add(threadListener);
        threadListener.start();

        Thread threadMineur = new Thread(() -> {
            // ce thread essaie continuellement de miner des blocs de transactions
            // (Celui est en action seulement lorsqu'il y a des transactions à mettre en blocs dans le mempool)
            listenToMempool();
        }, "threadMineur" + port + "EssaiDeMiner");
        threadConnexions.add(threadMineur);
        threadMineur.start();
    }

    public ArrayList<Miner> getAllOtherMiners() {
        ArrayList<Miner> otherMiners = new ArrayList<>();
        for (Miner miner : allMiners) {
            if (miner != this) {
                otherMiners.add(miner);
            }
        }
        return otherMiners;
    }

    //used whenever a miner finds a branch longer than its blockchain
    private void setLongestChain(){
        for (ArrayList<IBlock> branch : branches) {
            if (branch.size() > blockchain.size()) {
                blockchain = new ArrayList<>(branch);
                logInConsole("Chaîne la plus longue définie par le mineur " + id);
            }
        }

        branches.clear(); // on abandonne toutes les autres branches après avoir défini celle officielle
    }

    private IBlock mineBlock() {
        // Mining logic goes here.
        ArrayList<Transaction> transactionsToConfirm = (ArrayList<Transaction>) mempool;

        logInConsole("Création d'un nouveau bloc en cours");

        if (transactionsToConfirm.size() > BLOCK_SIZE) {
            // trop de transactions pour un seul bloc, donc il faut seulement mettre BLOCK_SIZE (nombre) de transactions dans le bloc
            transactionsToConfirm = new ArrayList<>();

            for (int i = 0; i < BLOCK_SIZE; i++) {
                transactionsToConfirm.add(mempool.get(i));
            }
        }

        mempool.removeAll(transactionsToConfirm); // on fait ça de cette manière pour que cette opération supporte le fait qu'on puisse recevoir de nouvelles transactions même durant celle-ci

        // on converti les transactions à juste leurs ids
        List<Integer> idsOfTransactionsToConfirm = new ArrayList<>();

        for (Transaction t: transactionsToConfirm) {
            idsOfTransactionsToConfirm.add(t.transactionId);
        }

        Block newBlockToMine = null;

        if (blockchain.size() == 0) {
            // il n'y a pas d'autres blocks, donc nous allons créé/miné un bloc genèse
            newBlockToMine = new Block(new ArrayList<>());
        } else {
            // un bloc normal (pas de genèse)
            Block lastBlock = (Block) blockchain.get(blockchain.size() - 1);

            // on peut prendre directement son hash (puisque celui-ci doit déjà avoir été hashé lors de son proof of work/lors qu'il a été miné)
            newBlockToMine = new Block(lastBlock.blockHash, idsOfTransactionsToConfirm, lastBlock.depth);
        }

        /**
         * On mine
         */

        // proof-of-worf/on mine
        newBlockToMine.blockHash = newBlockToMine.calculateBlockHash();

        return newBlockToMine; // Replace with an actual IBlock implementation.
    }

    private void addBlock(Block block) {
        // Logic to add a block to the end of the chain.
        blockchain.add(block);
        logInConsole("Bloc ajouté par le mineur " + id);
        for (Miner neighbor : getAllOtherMiners()) {
            neighbor.synchronise();
        }
    }

    private void addToMemPool(Transaction tx){
        logInConsole("Transaction ajoutée à la mempool par le mineur " + id);
        mempool.add(tx);
    }

    public ArrayList<IBlock> init() {
        // Initialize the genesis block.
        Block newGenesisBlock = (Block) mineBlock();

        logInConsole("Bloc de genèse créé par le mineur " + id);
        addBlock(newGenesisBlock);

        return blockchain; // Return initialized blockchain.
    }

    public ArrayList<Integer> connect() throws IOException {
        ArrayList<Integer> connectedNodes = new ArrayList<>();
        for (Miner miner : getAllOtherMiners()) {
            connectedNodes.add(miner.id);
        }
        this.neighborNodes = connectedNodes;
        logInConsole("Mineur " + id + " connecté aux nœuds : " + connectedNodes);
        return connectedNodes;
    }

    //calls mineBlock method whenever it collects transactions and validates received blocks and adds it to the current chain
    public void listenToNetwork()throws IOException{
        socket = new DatagramSocket(port); // crée un socket écoutant sur ce port de localhost
        byte bufferToReceive[] = new byte[1024];
        // this datagramPacket represents a request received by the miner
        DatagramPacket datagramPacketOfRequestReceived = new DatagramPacket(bufferToReceive, 1024);

        logInConsole("Prepared to accept requests");

        while (true) {
            // this function waits until a request is received
            socket.receive(datagramPacketOfRequestReceived);

            String messageOfRequest = new String(datagramPacketOfRequestReceived.getData(), 0, datagramPacketOfRequestReceived.getLength());

            handleRequest(messageOfRequest, datagramPacketOfRequestReceived);

            // EXEMPLE POUR TEST: sendResponseMessageToARequest("Response: " + messageOfRequest, datagramPacketOfRequestReceived);
        }
    }

    private void listenToMempool() {
        while (true) {
            if (mempool.size() == 0 && blockchain.size() != 0) {
                // aucunes transactions à mettre en blocs, donc on arrête (à moins, qu'il s'agit du bloc genèse (puisque n'a pas besoin de contenir de transactions))
                continue;
            }

            Block newBlock = (Block) mineBlock();
            addBlock(newBlock);
            logInConsole("Il y a dorénavant " + blockchain.size() + " blocks dans le blockchain.");
        }
    }

    public ArrayList<Block> synchronise() throws IOException{
           ArrayList<Block> longestChain = new ArrayList<>(blockchain); // Copie locale de la chaîne actuelle

        for (Miner neighbor : getAllOtherMiners()) {
            ArrayList<IBlock> neighborChain = neighbor.blockchain; // Accéder à la blockchain d'un voisin

            // Vérifier si la chaîne du voisin est valide et plus longue que la chaîne actuelle
            if (neighborChain.size() > longestChain.size()) {
                longestChain = new ArrayList<>(neighborChain);
            }
        }

        // Mettre à jour la blockchain locale si une chaîne plus longue est trouvée
        if (longestChain.size() > blockchain.size()) {
            blockchain = new ArrayList<>(longestChain);
            logInConsole("La blockchain a été synchronisée avec la chaîne la plus longue trouvée.");
        } else {
            logInConsole("Aucune chaîne plus longue trouvée. Pas de synchronisation nécessaire.");
        }

        return blockchain; // Retourne la blockchain synchronisée
    }

    private boolean validateBlock(IBlock previousBlock, IBlock currentBlock){
        //return ((Block) currentBlock).getPreviousHash().equals(((Block) previousBlock).getCurrentHash()) && currentBlock.isValid();
        return true; // TODO to change
    }

    public int getPort() {
        return port;
    }

    private void handleRequest(String message, DatagramPacket datagramPacketOfRequest) {
        logInConsole("Requête reçue (" + message + ")");

        if (message.contains(":") && (Objects.equals(message.split(":")[0], "TRANSACTION"))) {
            // la requête s'agit d'une transaction
            try {
                Transaction transactionObtenue = Transaction.deserializeThisTransaction(message.split(":")[1]);

                addToMemPool(transactionObtenue);
                associationTransactionIdAvecInfosClient.put(transactionObtenue.transactionId, datagramPacketOfRequest);

                System.out.println("Transaction reçu #" + transactionObtenue.transactionId);
            } catch (Exception e) {
                logInConsole("An error occured during the deserialization of a transaction: " + e.getMessage());
            }
        }
    }

    private void sendResponseMessageToARequest(String message, DatagramPacket datagramPacketOfRequest) throws IOException {
        // On envoie le message au client ayant envoyé cette requête (selon les infos du client envoyés dans celle-ci)
        trySendingMessage(message, datagramPacketOfRequest.getAddress(), datagramPacketOfRequest.getPort());
    }

    private void trySendingMessage(String message, InetAddress address, int port) throws IOException {
        DatagramPacket datagramPacketToSendRequest = new DatagramPacket(new byte[1024], 1024);
        datagramPacketToSendRequest.setPort(port);
        datagramPacketToSendRequest.setAddress(address);
        datagramPacketToSendRequest.setLength(message.length());
        datagramPacketToSendRequest.setData(message.getBytes());

        socket.send(datagramPacketToSendRequest);
    }

    private void logInConsole(String message) {
        System.out.println("Dans miner du port #" + this.port + " : " + message);
    }
}
