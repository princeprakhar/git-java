import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.InflaterInputStream;
import java.util.zip.DeflaterOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.time.Instant;
import java.net.URL;

public class Main {
    // SHA-1 hash calculation
    public static String sha1Hex(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] sha1Bytes = md.digest(input);

            StringBuilder hexString = new StringBuilder();
            for (byte b : sha1Bytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 algorithm not found", e);
        }
    }

    // cat-file command handler
    public static void catFileHandler(String hash) throws IOException {
        String objectPath = shaToPath(hash);

        try (InflaterInputStream inflaterStream = new InflaterInputStream(new FileInputStream(objectPath));
             BufferedReader reader = new BufferedReader(new InputStreamReader(inflaterStream))) {

            StringBuilder content = new StringBuilder();
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine) {
                    // Skip the header in the first line
                    int nullIndex = line.indexOf('\0');
                    if (nullIndex != -1) {
                        line = line.substring(nullIndex + 1);
                    }
                    firstLine = false;
                }
                content.append(line);
            }

            System.out.print(content);
        } catch (IOException e) {
            throw new IOException("Error reading object file: " + hash, e);
        }
    }

    // Convert SHA-1 hash to file path
    private static String shaToPath(String sha) {
        return String.format(".git/objects/%s/%s", sha.substring(0, 2), sha.substring(2));
    }

    // hash-object command handler
    public static String createBlobObject(String fileName, boolean write) throws IOException {
        try {
            byte[] fileContents = Files.readAllBytes(Paths.get(fileName));
            String header = "blob " + fileContents.length + "\0";
            byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);

            byte[] fullContent = new byte[headerBytes.length + fileContents.length];
            System.arraycopy(headerBytes, 0, fullContent, 0, headerBytes.length);
            System.arraycopy(fileContents, 0, fullContent, headerBytes.length, fileContents.length);

            String sha1Hash = sha1Hex(fullContent);

            if (write) {
                String blobPath = shaToPath(sha1Hash);
                File blobFile = new File(blobPath);
                blobFile.getParentFile().mkdirs();
                try (DeflaterOutputStream out = new DeflaterOutputStream(new FileOutputStream(blobFile))) {
                    out.write(fullContent);
                }
            }

            return sha1Hash;
        } catch (IOException e) {
            throw new IOException("Error processing file: " + fileName, e);
        }
    }

    // init command handler
    public static void initRepository() throws IOException {
        File root = new File(".git");
        new File(root, "objects").mkdirs();
        new File(root, "refs").mkdirs();
        File head = new File(root, "HEAD");

        try {
            head.createNewFile();
            Files.write(head.toPath(), "ref: refs/heads/main\n".getBytes(StandardCharsets.UTF_8));
            System.out.println("Initialized git directory");
        } catch (IOException e) {
            throw new IOException("Error initializing git repository", e);
        }
    }

    // ls-tree command handler
    public static void lsTreeHandler(String[] args) throws IOException {
        if (args.length < 3) {
            throw new IllegalArgumentException("Usage: java Main ls-tree --name-only <tree-ish>");
        }

        boolean nameOnly = args[1].equals("--name-only");
        String treeIsh = args[2];

        List<String> entries = readTreeObject(treeIsh);

        if (nameOnly) {
            List<String> names = new ArrayList<>();
            for (String entry : entries) {
                names.add(entry.split("\t")[1]);
            }
            Collections.sort(names);
            for (String name : names) {
                System.out.println(name);
            }
        } else {
            for (String entry : entries) {
                System.out.println(entry);
            }
        }
    }

    // Helper method to read and parse a tree object
    private static List<String> readTreeObject(String hash) throws IOException {
        String objectPath = shaToPath(hash);
        List<String> entries = new ArrayList<>();

        try (InflaterInputStream inflaterStream = new InflaterInputStream(new FileInputStream(objectPath));
             DataInputStream dataIn = new DataInputStream(inflaterStream)) {

            // Read and verify the header
            String header = readNullTerminatedString(dataIn);
            if (!header.startsWith("tree ")) {
                throw new IOException("Invalid tree object header");
            }

            // Read entries
            while (dataIn.available() > 0) {
                String mode = readUntilSpace(dataIn);
                String name = readNullTerminatedString(dataIn);
                byte[] sha = new byte[20];
                dataIn.readFully(sha);
                String shaHex = bytesToHex(sha);

                entries.add(String.format("%s %s %s\t%s", mode,
                        mode.startsWith("100") ? "blob" : "tree", shaHex, name));
            }
        }

        Collections.sort(entries);
        return entries;
    }

    // Helper method to read a null-terminated string
    private static String readNullTerminatedString(DataInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) != 0) {
            sb.append((char) ch);
        }
        return sb.toString();
    }

    // Helper method to read until a space character
    private static String readUntilSpace(DataInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) != ' ') {
            sb.append((char) ch);
        }
        return sb.toString();
    }

    // Helper method to convert bytes to hexadecimal string
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static void writeTreeHandler() throws IOException {
        String treeHash = writeTree(Paths.get("."));
        System.out.print(treeHash);
    }

    // Recursive method to write tree objects
    private static String writeTree(Path dir) throws IOException {
        ByteArrayOutputStream treeContent = new ByteArrayOutputStream();
        Files.list(dir).sorted().forEach(path -> {
            try {
                String relativePath = dir.relativize(path).toString();
                if (Files.isDirectory(path)) {
                    if (!relativePath.equals(".git")) {
                        String subTreeHash = writeTree(path);
                        writeTreeEntry(treeContent, "40000", relativePath, subTreeHash);
                    }
                } else {
                    String blobHash = createBlobObject(path.toString(), true);
                    String mode = Files.isExecutable(path) ? "100755" : "100644";
                    writeTreeEntry(treeContent, mode, relativePath, blobHash);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        byte[] content = treeContent.toByteArray();
        String header = "tree " + content.length + "\0";
        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);

        byte[] fullContent = new byte[headerBytes.length + content.length];
        System.arraycopy(headerBytes, 0, fullContent, 0, headerBytes.length);
        System.arraycopy(content, 0, fullContent, headerBytes.length, content.length);

        String treeHash = sha1Hex(fullContent);
        String treePath = shaToPath(treeHash);
        File treeFile = new File(treePath);
        treeFile.getParentFile().mkdirs();
        try (DeflaterOutputStream out = new DeflaterOutputStream(new FileOutputStream(treeFile))) {
            out.write(fullContent);
        }

        return treeHash;
    }

    // Helper method to write a tree entry
    private static void writeTreeEntry(ByteArrayOutputStream out, String mode, String name, String hash) throws IOException {
        out.write(String.format("%s %s\0", mode, name).getBytes(StandardCharsets.UTF_8));
        out.write(hexToBytes(hash));
    }

    // Helper method to convert hexadecimal string to bytes
    private static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }

    // commit-tree command handler
    public static String commitTreeHandler(String treeHash, String parentHash, String message) throws IOException {
        String timestamp = Instant.now().toString();
        String author = "John Doe <john@example.com>";
        String committer = "John Doe <john@example.com>";

        StringBuilder commitContent = new StringBuilder();
        commitContent.append("tree ").append(treeHash).append("\n");
        if (parentHash != null && !parentHash.isEmpty()) {
            commitContent.append("parent ").append(parentHash).append("\n");
        }
        commitContent.append("author ").append(author).append(" ").append(timestamp).append("\n");
        commitContent.append("committer ").append(committer).append(" ").append(timestamp).append("\n");
        commitContent.append("\n").append(message).append("\n");

        byte[] content = commitContent.toString().getBytes(StandardCharsets.UTF_8);
        String header = "commit " + content.length + "\0";
        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);

        byte[] fullContent = new byte[headerBytes.length + content.length];
        System.arraycopy(headerBytes, 0, fullContent, 0, headerBytes.length);
        System.arraycopy(content, 0, fullContent, headerBytes.length, content.length);

        String commitHash = sha1Hex(fullContent);
        String commitPath = shaToPath(commitHash);
        File commitFile = new File(commitPath);
        commitFile.getParentFile().mkdirs();
        try (DeflaterOutputStream out = new DeflaterOutputStream(new FileOutputStream(commitFile))) {
            out.write(fullContent);
        }

        return commitHash;
    }

    // initRepository to particular dir
    public static void initRepository(String targetDir) throws IOException {
        File root = new File(targetDir,".git");
        new File(root, "objects").mkdirs();
        new File(root, "refs").mkdirs();
        File head = new File(root, "HEAD");

        try {
            head.createNewFile();
            Files.write(head.toPath(), "ref: refs/heads/main\n".getBytes(StandardCharsets.UTF_8));
            System.out.println("Initialized git directory at: "+ targetDir );
        } catch (IOException e) {
            throw new IOException("Error initializing git repository", e);
        }
    }





    // Clone command handler
    public static void cloneRepository(String repoUrl, String targetDir) throws IOException {
        // Create target directory
        Files.createDirectories(Paths.get(targetDir));

        // Initialize repository
        initRepository(targetDir);

        // Fetch repository data
        byte[] packData = fetchPackData(repoUrl);

        // Process pack data
        processPackData(packData, targetDir);

        System.out.println("Repository cloned successfully.");
    }

    private static byte[] fetchPackData(String repoUrl) throws IOException {
        URL url = new URL(repoUrl + "/info/refs?service=git-upload-pack");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            String headRef = null;
            while ((line = reader.readLine()) != null) {
                if (line.contains("refs/heads/main")) {
                    headRef = line.split(" ")[0];
                    break;
                }
            }

            if (headRef == null) {
                throw new IOException("Could not find main branch reference");
            }

            url = new URL(repoUrl + "/git-upload-pack");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                String request = "0032want " + headRef + "\n00000009done\n";
                os.write(request.getBytes(StandardCharsets.UTF_8));
            }

            try (InputStream is = conn.getInputStream()) {
                return is.readAllBytes();
            }
        }
    }

    private static void processPackData(byte[] packData, String targetDir) throws IOException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(packData);
             InflaterInputStream iis = new InflaterInputStream(bis)) {

            // Skip pack header
            iis.skip(12);

            int objectCount = readInt(iis);

            for (int i = 0; i < objectCount; i++) {
                int objectType = (iis.read() >> 4) & 7;
                long size = readVariableLengthInteger(iis);

                byte[] objectData = new byte[(int) size];
                iis.read(objectData);

                String objectHash = sha1Hex(objectData);
                String objectPath = targetDir + "/.git/objects/" + objectHash.substring(0, 2) + "/" + objectHash.substring(2);

                Files.createDirectories(Paths.get(objectPath).getParent());
                try (OutputStream os = new FileOutputStream(objectPath)) {
                    os.write(objectData);
                }
            }
        }
    }

    private static int readInt(InputStream is) throws IOException {
        byte[] intBytes = new byte[4];
        is.read(intBytes);
        return ((intBytes[0] & 0xFF) << 24) |
                ((intBytes[1] & 0xFF) << 16) |
                ((intBytes[2] & 0xFF) << 8)  |
                (intBytes[3] & 0xFF);
    }

    private static long readVariableLengthInteger(InputStream is) throws IOException {
        long value = 0;
        int shift = 0;
        int b;

        do {
            b = is.read();
            value |= (long) (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);

        return value;
    }




    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Please provide a command");
            return;
        }

        String command = args[0];

        try {
            switch (command) {
                case "init":
                    initRepository();
                    break;
                case "cat-file":
                    if (args.length < 3 || !args[1].equals("-p")) {
                        throw new IllegalArgumentException("Usage: java Main cat-file -p <object>");
                    }
                    catFileHandler(args[2]);
                    break;
                case "hash-object":
                    if (args.length < 2) {
                        throw new IllegalArgumentException("Usage: java Main hash-object [-w] <file>");
                    }
                    boolean write = args[1].equals("-w");
                    String fileName = write ? args[2] : args[1];
                    System.out.println(createBlobObject(fileName, write));
                    break;
                case "ls-tree":
                    lsTreeHandler(args);
                    break;
                case "write-tree":
                    writeTreeHandler();
                    break;
                case "commit-tree":
                    if (args.length < 6) {
                        throw new IllegalArgumentException("Usage: java Main commit-tree <tree> -p <parent> -m <message>");
                    }
                    String treeHash = args[1];
                    String parentHash = args[3];
                    String message = args[5];
                    String commitHash = commitTreeHandler(treeHash, parentHash, message);
                    System.out.println(commitHash);
                    break;
                case "clone":
                    if (args.length != 3) {
                        throw new IllegalArgumentException("Usage: java Main clone <repository-url> <target-directory>");
                    }
                    cloneRepository(args[1], args[2]);
                    break;
                default:
                    System.out.println("Unknown command: " + command);
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
