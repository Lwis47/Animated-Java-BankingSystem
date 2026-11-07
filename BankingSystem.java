import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.*;
import java.security.*;
import java.text.SimpleDateFormat;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.sound.sampled.*;

/** Local console ATM demonstration. */
public class BankingSystem {
    private static final Path DATA_FILE = Paths.get("accounts.bank");
    private static final Path LEGACY_DATA_FILE = Paths.get("accounts.dat");
    private static final Path ADMIN_FILE = Paths.get("admin.properties");
    private static final int DATA_MAGIC = 0x4359424B; // CYBK
    private static final int DATA_VERSION = 1;
    private static final int MAX_ACCOUNTS = 10_000;
    private static final int MAX_TRANSACTIONS = 2_000;
    private static final long MAX_TRANSACTION_CENTS = 1_000_000_000L;
    private static final int HASH_ITERATIONS = 150_000;
    private static final int HASH_BYTES = 32;
    private static final String BANK_NAME = "CYPHER BANK";
    private static final String TERMINAL_ID = "CBK-NAI-0027";
    private static final String TERMINAL_LOCATION = "NAIROBI, KENYA";
    private static final int TYPEWRITER_DELAY_MS = 18;
    private static final Scanner scanner = new Scanner(System.in);
    private static final ArrayList<Account> accounts = new ArrayList<>();
    private static final boolean ANSI = System.console() != null
        && System.getenv("NO_COLOR") == null;
    private static final boolean SOUND_ENABLED = !hasProgramFlag("--silent");
    private static final boolean ANIMATIONS_ENABLED = !hasProgramFlag("--no-animation");

    private static final String RESET = color("0");
    private static final String BOLD = color("1");
    // ATM palette: navy machine panel, ice-blue screen text, amber prompts.
    private static final String NAVY = color("44");
    private static final String WHITE = color("97");
    private static final String RED = color("91");
    private static final String GREEN = color("92");
    private static final String YELLOW = color("93");
    private static final String BLUE = color("94");
    private static final String CYAN = color("96");

    private static boolean accountDataLoadFailed;
    private static boolean adminDataLoadFailed;
    private static AdminCredentials administrator;

    public static void main(String[] args) {
        loadAccounts();
        loadAdministrator();
        if (!accountDataLoadFailed) {
            seedAccountsIfEmpty();
        }
        welcomeScreen();
        mainMenu();
        scanner.close();
    }

    // Serializable only for the one-time, filtered legacy import. All new data
    // uses the validated binary store below instead of Java serialization.
    static class Account implements Serializable {
        private static final long serialVersionUID = 1L;
        private String name;
        private String accountNumber;
        private String pin;             // Legacy only; cleared when migrated.
        private String pinSalt;
        private String pinHash;
        private double balance;         // Legacy only; values now use cents.
        private long balanceCents;
        private boolean blocked;
        private ArrayList<String> transactions;

        Account() { }

        Account(String name, String accountNumber, String newPin, long openingCents) {
            this.name = name;
            this.accountNumber = accountNumber;
            this.balanceCents = openingCents;
            this.transactions = new ArrayList<>();
            setPin(newPin);
            addTransaction("ACCOUNT CREATED | Opening balance: " + money(openingCents));
        }

        void setPin(String newPin) {
            byte[] salt = randomBytes(16);
            pinSalt = Base64.getEncoder().encodeToString(salt);
            pinHash = hashSecret(newPin.toCharArray(), salt);
            pin = null;
        }

        boolean matchesPin(String candidate) {
            return pinHash != null && pinSalt != null
                && verifySecret(candidate.toCharArray(), pinSalt, pinHash);
        }

        void migrateLegacyFields() throws IOException {
            if (transactions == null) transactions = new ArrayList<>();
            if (pinHash == null || pinSalt == null) {
                if (pin == null || !pin.matches("\\d{4}")) {
                    throw new IOException("A legacy account is missing a valid PIN.");
                }
                setPin(pin);
            }
            if (balanceCents == 0 && balance != 0.0d) {
                balanceCents = toCents(BigDecimal.valueOf(balance));
            }
            balance = 0.0d;
            pin = null;
        }

        void addTransaction(String text) {
            if (transactions == null) transactions = new ArrayList<>();
            if (transactions.size() >= MAX_TRANSACTIONS) transactions.remove(0);
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            transactions.add(time + " | " + text);
        }
    }

    private static final class AdminCredentials {
        final String username;
        final String salt;
        final String hash;
        AdminCredentials(String username, String salt, String hash) {
            this.username = username;
            this.salt = salt;
            this.hash = hash;
        }
    }

    private enum Sound { START, ACCEPT, DISPENSE, ERROR }

    // ------------------------------------------------------------------
    // ATM SCREENS
    // ------------------------------------------------------------------

    private static void welcomeScreen() {
        clearScreen();
        panelBorder();
        panelLine("   ____ _   _ ____  _   _ _____ ____");
        panelLine("  / ___| | | |  _ \\| | | | ____|  _ \\");
        panelLine(" | |   | |_| | |_) | |_| |  _| | |_) |");
        panelLine(" | |___|  _  |  __/|  _  | |___|  _ <");
        panelLine("  \\____|_| |_|_|   |_| |_|_____|_| \\_\\");
        panelLine("              B A N K   A T M");
        panelLine("  " + BANK_NAME + " / SECURE SELF-SERVICE BANKING");
        panelLine("  " + TERMINAL_ID + " / " + TERMINAL_LOCATION);
        panelBorder();
        atmSequence("STARTING SECURE SESSION", 5, 75, Sound.START);
        animatedInstruction("TERMINAL READY - SELECT A SERVICE");
        pause(650);
    }

    private static void mainMenu() {
        while (true) {
            clearScreen();
            header("WELCOME");
            System.out.println("  " + GREEN + "SERVICE STATUS: AVAILABLE" + RESET);
            System.out.println("  Secure access requires your account number and PIN.");
            line();
            menuItem(1, "Access my account");
            menuItem(2, "ATM information");
            menuItem(3, "Staff administration");
            menuItem(4, "End session");
            footer();
            switch (readInt("Select an option: ", 1, 4)) {
                case 1: customerLogin(); break;
                case 2: aboutBank(); break;
                case 3: adminLogin(); break;
                case 4: goodbye(); return;
                default: break;
            }
        }
    }

    private static void customerLogin() {
        clearScreen();
        header("CARD & PIN ACCESS");
        animatedInstruction("ENTER YOUR ACCOUNT NUMBER, THEN YOUR PIN");
        System.out.println(YELLOW + "  FOR YOUR SECURITY, THREE INCORRECT PINS LOCK ACCESS." + RESET);
        String accountNo = readNonEmpty("\n  Account number: ");
        Account account = findAccount(accountNo);
        if (account == null) { error("Card/account not recognised."); return; }
        if (account.blocked) { error("This account is locked. Please contact the bank."); return; }
        for (int attempt = 1; attempt <= 3; attempt++) {
            String pin = readPin("  PIN: ");
            if (account.matchesPin(pin)) {
                atmSequence("VERIFYING PIN", 4, 75, Sound.ACCEPT);
                success("PIN accepted. Welcome, " + account.name + ".");
                pause(700);
                customerMenu(account);
                return;
            }
            if (attempt < 3) error("Incorrect PIN. Attempts remaining: " + (3 - attempt));
        }
        account.blocked = true;
        account.addTransaction("ACCOUNT LOCKED AFTER FAILED PIN ATTEMPTS");
        saveAccounts();
        error("Too many incorrect attempts. Account locked.");
    }

    private static void customerMenu(Account account) {
        while (true) {
            clearScreen();
            header("ACCOUNT SERVICES");
            System.out.println("  Welcome back, " + BOLD + account.name + RESET);
            System.out.println("  Account: " + CYAN + maskAccount(account.accountNumber) + RESET);
            System.out.println("  Available balance: " + GREEN + BOLD + money(account.balanceCents) + RESET);
            line();
            menuItem(1, "Balance inquiry");
            menuItem(2, "Cash deposit");
            menuItem(3, "Cash withdrawal");
            menuItem(4, "Transfer money");
            menuItem(5, "Mini statement");
            menuItem(6, "Change PIN");
            menuItem(7, "Account details");
            menuItem(8, "Return card and end session");
            footer();
            switch (readInt("Select an option: ", 1, 8)) {
                case 1: checkBalance(account); break;
                case 2: deposit(account); break;
                case 3: withdraw(account); break;
                case 4: transfer(account); break;
                case 5: transactionHistory(account); break;
                case 6: changePin(account); break;
                case 7: accountDetails(account); break;
                case 8:
                    atmSequence("RETURNING CARD", 3, 75, Sound.ACCEPT);
                    success("Session closed. Please take your card.");
                    pause(650);
                    return;
                default: break;
            }
        }
    }

    private static void checkBalance(Account account) {
        clearScreen();
        header("BALANCE INQUIRY");
        System.out.println("\n  Account holder     : " + account.name);
        System.out.println("  Account reference  : " + maskAccount(account.accountNumber));
        System.out.println("  Available balance  : " + GREEN + BOLD + money(account.balanceCents) + RESET);
        line();
        success("Balance retrieved.");
        pausePrompt();
    }

    private static void deposit(Account account) {
        clearScreen();
        header("CASH DEPOSIT");
        System.out.println("  Current balance: " + money(account.balanceCents));
        long amount = readPositiveAmount("  Amount to deposit (KSh): ");
        if (amount > MAX_TRANSACTION_CENTS) {
            error("A single deposit cannot exceed " + money(MAX_TRANSACTION_CENTS) + ".");
            return;
        }
        atmSequence("COUNTING NOTES", 6, 120, Sound.ACCEPT);
        try {
            account.balanceCents = Math.addExact(account.balanceCents, amount);
        } catch (ArithmeticException e) {
            error("The resulting balance is too large.");
            return;
        }
        account.addTransaction("DEPOSIT | Amount: " + money(amount) + " | Balance: " + money(account.balanceCents));
        if (saveAccounts()) printReceipt("CASH DEPOSIT", account, amount, account.balanceCents, "APPROVED");
        pausePrompt();
    }

    private static void withdraw(Account account) {
        clearScreen();
        header("CASH WITHDRAWAL");
        System.out.println("  Available balance: " + money(account.balanceCents));
        System.out.println("  Common amounts: KSh 1,000 / 2,000 / 5,000 / 10,000");
        long amount = readPositiveAmount("  Amount to withdraw (KSh): ");
        if (amount > account.balanceCents) {
            error("Insufficient funds. Available: " + money(account.balanceCents));
            return;
        }
        atmSequence("AUTHORIZING WITHDRAWAL", 4, 120, Sound.ACCEPT);
        atmSequence("DISPENSING CASH", 5, 95, Sound.DISPENSE);
        account.balanceCents -= amount;
        account.addTransaction("WITHDRAWAL | Amount: " + money(amount) + " | Balance: " + money(account.balanceCents));
        if (saveAccounts()) printReceipt("CASH WITHDRAWAL", account, amount, account.balanceCents, "APPROVED");
        pausePrompt();
    }

    private static void transfer(Account sender) {
        clearScreen();
        header("TRANSFER MONEY");
        System.out.println("  From account : " + sender.accountNumber);
        System.out.println("  Balance      : " + money(sender.balanceCents));
        String targetNo = readNonEmpty("\n  Recipient account number: ");
        if (targetNo.equals(sender.accountNumber)) { error("You cannot transfer to the same account."); return; }
        Account receiver = findAccount(targetNo);
        if (receiver == null) { error("Recipient account not found."); return; }
        if (receiver.blocked) { error("Recipient account is locked."); return; }
        System.out.println("  Recipient    : " + BOLD + receiver.name + RESET);
        long amount = readPositiveAmount("  Amount to transfer (KSh): ");
        if (amount > sender.balanceCents) { error("Insufficient funds."); return; }
        if (!readNonEmpty("  Confirm transfer? (Y/N): ").equalsIgnoreCase("Y")) {
            warning("Transfer cancelled."); pausePrompt(); return;
        }
        atmSequence("SECURING TRANSFER", 5, 115, Sound.ACCEPT);
        try {
            receiver.balanceCents = Math.addExact(receiver.balanceCents, amount);
        } catch (ArithmeticException e) {
            error("The recipient balance is too large.");
            return;
        }
        sender.balanceCents -= amount;
        sender.addTransaction("TRANSFER TO " + receiver.accountNumber + " (" + receiver.name + ") | Amount: "
            + money(amount) + " | Balance: " + money(sender.balanceCents));
        receiver.addTransaction("TRANSFER FROM " + sender.accountNumber + " (" + sender.name + ") | Amount: "
            + money(amount) + " | Balance: " + money(receiver.balanceCents));
        if (saveAccounts()) printReceipt("TRANSFER TO " + receiver.accountNumber, sender, amount, sender.balanceCents, "APPROVED");
        pausePrompt();
    }

    private static void transactionHistory(Account account) {
        clearScreen();
        header("MINI STATEMENT");
        if (account.transactions.isEmpty()) {
            warning("No transactions available.");
        } else {
            int start = Math.max(0, account.transactions.size() - 15);
            for (int i = account.transactions.size() - 1; i >= start; i--) {
                System.out.println("  " + account.transactions.get(i));
            }
            if (start > 0) System.out.println(YELLOW + "\n  Latest 15 transactions shown." + RESET);
        }
        pausePrompt();
    }

    private static void changePin(Account account) {
        clearScreen();
        header("CHANGE PIN");
        if (!account.matchesPin(readPin("  Current PIN: "))) { error("Current PIN is incorrect."); return; }
        String newPin = readNewPin();
        if (!newPin.equals(readPin("  Confirm new PIN: "))) { error("PINs do not match."); return; }
        account.setPin(newPin);
        account.addTransaction("PIN CHANGED");
        saveAccounts();
        success("PIN changed successfully.");
        pausePrompt();
    }

    private static void accountDetails(Account account) {
        clearScreen();
        header("ACCOUNT DETAILS");
        System.out.println("\n  Name         : " + account.name);
        System.out.println("  Account No.  : " + account.accountNumber);
        System.out.println("  Balance      : " + money(account.balanceCents));
        System.out.println("  Status       : " + (account.blocked ? RED + "LOCKED" : GREEN + "ACTIVE") + RESET);
        System.out.println("  Transactions : " + account.transactions.size());
        pausePrompt();
    }

    private static void registerAccount() {
        clearScreen();
        header("OPEN NEW ACCOUNT");
        String name = readName("  Full name: ");
        String pin = readNewPin();
        long opening = readNonNegativeAmount("  Opening deposit (KSh): ");
        if (opening > MAX_TRANSACTION_CENTS) { error("Opening deposit cannot exceed " + money(MAX_TRANSACTION_CENTS) + "."); return; }
        Account account = new Account(name, generateAccountNumber(), pin, opening);
        accounts.add(account);
        atmSequence("CREATING ACCOUNT", 5, 120, Sound.ACCEPT);
        if (saveAccounts()) {
            success("ACCOUNT CREATED");
            System.out.println("\n  Name        : " + name);
            System.out.println("  Account No. : " + CYAN + account.accountNumber + RESET);
            System.out.println("  Opening     : " + money(opening));
            warning("Keep your account number and PIN private.");
        }
        pausePrompt();
    }

    // ------------------------------------------------------------------
    // ADMINISTRATION
    // ------------------------------------------------------------------

    private static void adminLogin() {
        if (!ensureAdministratorConfigured()) return;
        clearScreen();
        header("ADMINISTRATOR LOGIN");
        String user = readNonEmpty("  Username: ");
        char[] pass = readSecret("  Password: ");
        boolean authenticated = user.equals(administrator.username) && verifySecret(pass, administrator.salt, administrator.hash);
        Arrays.fill(pass, '\0');
        if (!authenticated) { error("Invalid administrator credentials."); return; }
        atmSequence("VERIFYING CREDENTIALS", 4, 115, Sound.ACCEPT);
        success("Administrator access granted.");
        pause(3000);
        adminMenu();
    }

    private static boolean ensureAdministratorConfigured() {
        if (administrator != null) return true;
        if (adminDataLoadFailed) {
            error("Administrator settings could not be loaded; setup is disabled to protect them.");
            return false;
        }
        clearScreen();
        header("FIRST-TIME ADMIN SETUP");
        System.out.println(YELLOW + "  Create the local administrator profile." + RESET);
        String username = readAdminUsername();
        char[] password = readNewAdminPassword();
        char[] confirm = readSecret("  Confirm password: ");
        if (!Arrays.equals(password, confirm)) {
            Arrays.fill(password, '\0'); Arrays.fill(confirm, '\0');
            error("Passwords do not match. Setup cancelled.");
            return false;
        }
        byte[] salt = randomBytes(16);
        administrator = new AdminCredentials(username, Base64.getEncoder().encodeToString(salt), hashSecret(password, salt));
        Arrays.fill(password, '\0'); Arrays.fill(confirm, '\0');
        if (!saveAdministrator()) { administrator = null; return false; }
        success("Administrator profile created. Please log in.");
        pause(4000);
        return true;
    }

    private static void adminMenu() {
        while (true) {
            clearScreen();
            header("ADMINISTRATOR CONTROL CENTER");
            menuItem(1, "View all accounts");
            menuItem(2, "Find an account");
            menuItem(3, "Bank statistics");
            menuItem(4, "Lock or unlock an account");
            menuItem(5, "Reset a customer PIN");
            menuItem(6, "Change my administrator password");
            menuItem(7, "Delete a zero-balance account");
            menuItem(8, "Log out");
            footer();
            switch (readInt("Select an option: ", 1, 8)) {
                case 1: viewAllAccounts(); break;
                case 2: searchAccount(); break;
                case 3: bankStatistics(); break;
                case 4: toggleBlock(); break;
                case 5: resetCustomerPin(); break;
                case 6: changeAdministratorPassword(); break;
                case 7: deleteAccount(); break;
                case 8: return;
                default: break;
            }
        }
    }

    private static void viewAllAccounts() {
        clearScreen();
        header("ALL CUSTOMER ACCOUNTS");
        if (accounts.isEmpty()) warning("No accounts found.");
        else {
            System.out.printf("  %-10s %-22s %15s  %s%n", "ACCOUNT", "NAME", "BALANCE", "STATUS");
            line();
            for (Account account : accounts) {
                String status = account.blocked ? RED + "LOCKED" : GREEN + "ACTIVE";
                System.out.printf("  %-10s %-22s %15s  %s%s%n", account.accountNumber, shorten(account.name, 22), money(account.balanceCents), status, RESET);
            }
        }
        pausePrompt();
    }

    private static void searchAccount() {
        clearScreen();
        header("FIND ACCOUNT");
        String query = readNonEmpty("  Account number or name: ").toLowerCase(Locale.ROOT);
        boolean found = false;
        for (Account account : accounts) {
            if (account.accountNumber.toLowerCase(Locale.ROOT).contains(query) || account.name.toLowerCase(Locale.ROOT).contains(query)) {
                System.out.println("\n  Account : " + account.accountNumber);
                System.out.println("  Name    : " + account.name);
                System.out.println("  Balance : " + money(account.balanceCents));
                System.out.println("  Status  : " + (account.blocked ? RED + "LOCKED" : GREEN + "ACTIVE") + RESET);
                found = true;
            }
        }
        if (!found) error("No matching account found.");
        pausePrompt();
    }

    private static void bankStatistics() {
        clearScreen();
        header("BANK STATISTICS");
        long total = 0;
        int active = 0, locked = 0;
        try {
            for (Account account : accounts) {
                total = Math.addExact(total, account.balanceCents);
                if (account.blocked) locked++; else active++;
            }
        } catch (ArithmeticException e) {
            error("Total customer funds exceeds the supported limit.");
            return;
        }
        System.out.println("\n  Total accounts       : " + accounts.size());
        System.out.println("  Active accounts      : " + GREEN + active + RESET);
        System.out.println("  Locked accounts      : " + RED + locked + RESET);
        System.out.println("  Total customer funds : " + GREEN + BOLD + money(total) + RESET);
        pausePrompt();
    }

    private static void toggleBlock() {
        clearScreen();
        header("LOCK / UNLOCK ACCOUNT");
        Account account = findAccount(readNonEmpty("  Account number: "));
        if (account == null) { error("Account not found."); return; }
        account.blocked = !account.blocked;
        account.addTransaction(account.blocked ? "ACCOUNT LOCKED BY ADMIN" : "ACCOUNT UNLOCKED BY ADMIN");
        saveAccounts();
        success("Account " + account.accountNumber + " is now " + (account.blocked ? "LOCKED." : "ACTIVE."));
        pausePrompt();
    }

    private static void resetCustomerPin() {
        clearScreen();
        header("RESET CUSTOMER PIN");
        System.out.println(YELLOW + "  Use this only after verifying the customer's identity." + RESET);
        Account account = findAccount(readNonEmpty("  Customer account number: "));
        if (account == null) { error("Account not found."); return; }

        System.out.println("  Customer: " + BOLD + account.name + RESET);
        String newPin = readNewPin();
        if (!newPin.equals(readPin("  Confirm new 4-digit PIN: "))) {
            error("PINs do not match. No change was made.");
            return;
        }

        account.setPin(newPin);
        account.blocked = false;
        account.addTransaction("PIN RESET BY ADMINISTRATOR");
        if (saveAccounts()) {
            success("PIN reset. Account access has been restored.");
        }
        pausePrompt();
    }

    private static void changeAdministratorPassword() {
        clearScreen();
        header("CHANGE ADMINISTRATOR PASSWORD");
        System.out.println(YELLOW + "  Confirm your current password before setting a new one." + RESET);

        char[] current = readSecret("  Current password: ");
        boolean valid = verifySecret(current, administrator.salt, administrator.hash);
        Arrays.fill(current, '\0');
        if (!valid) { error("Current password is incorrect."); return; }

        char[] newPassword = readNewAdminPassword();
        char[] confirmation = readSecret("  Confirm new password: ");
        if (!Arrays.equals(newPassword, confirmation)) {
            Arrays.fill(newPassword, '\0');
            Arrays.fill(confirmation, '\0');
            error("Passwords do not match. No change was made.");
            return;
        }

        byte[] salt = randomBytes(16);
        administrator = new AdminCredentials(
            administrator.username,
            Base64.getEncoder().encodeToString(salt),
            hashSecret(newPassword, salt)
        );
        Arrays.fill(newPassword, '\0');
        Arrays.fill(confirmation, '\0');
        if (saveAdministrator()) success("Administrator password changed successfully.");
        pausePrompt();
    }

    private static void deleteAccount() {
        clearScreen();
        header("DELETE ACCOUNT");
        Account account = findAccount(readNonEmpty("  Account number: "));
        if (account == null) { error("Account not found."); return; }
        if (account.balanceCents != 0) { error("Only accounts with an exact zero balance can be deleted."); return; }
        if (!readNonEmpty("  Type DELETE to confirm: ").equals("DELETE")) { warning("Deletion cancelled."); pausePrompt(); return; }
        accounts.remove(account);
        if (saveAccounts()) success("Account deleted.");
        pausePrompt();
    }

    private static void aboutBank() {
        clearScreen();
        header("ABOUT CYPHER BANK ATM");
        System.out.println("\n  A local Java ATM demonstration project.");
        System.out.println("  Features: secure account access, deposits, withdrawals, transfers,");
        System.out.println("  mini statements, PIN management, receipts, and session controls.");
        System.out.println("\n  Security: PINs/passwords are salted PBKDF2 hashes; account data");
        System.out.println("  is validated before loading and saves are written atomically.");
        System.out.println("\n  Run with --silent to disable ATM tones.");
        pausePrompt();
    }

    private static void printReceipt(String type, Account account, long amount, long balance, String status) {
        clearScreen();
        System.out.println(GREEN + BOLD);
        System.out.println("+--------------------------------------------------+");
        System.out.println("|          CYPHER BANK - ATM RECEIPT               |");
        System.out.println("+--------------------------------------------------+");
        receiptLine("TERMINAL", TERMINAL_ID);
        receiptLine("LOCATION", TERMINAL_LOCATION);
        receiptLine("DATE", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        receiptLine("REFERENCE", transactionReference());
        receiptLine("TYPE", type);
        receiptLine("ACCOUNT", maskAccount(account.accountNumber));
        receiptLine("AMOUNT", money(amount));
        receiptLine("BALANCE", money(balance));
        receiptLine("STATUS", status);
        System.out.println("+--------------------------------------------------+" + RESET);
        playSound(Sound.ACCEPT);
    }

    // ------------------------------------------------------------------
    // SAFE STORAGE AND ONE-TIME LEGACY IMPORT
    // ------------------------------------------------------------------

    private static void loadAccounts() {
        if (Files.exists(DATA_FILE)) {
            try { readSafeAccounts(); }
            catch (IOException e) {
                accountDataLoadFailed = true;
                System.err.println("Account data was not loaded: " + e.getMessage());
            }
            return;
        }
        if (Files.exists(LEGACY_DATA_FILE)) {
            try { importLegacyAccounts(); }
            catch (IOException | ClassNotFoundException e) {
                accountDataLoadFailed = true;
                System.err.println("Legacy account data was not imported: " + e.getMessage());
            }
        }
    }

    private static void readSafeAccounts() throws IOException {
        ArrayList<Account> loaded = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(DATA_FILE)))) {
            if (in.readInt() != DATA_MAGIC || in.readInt() != DATA_VERSION) throw new IOException("unrecognised account-data format");
            int count = readCount(in, MAX_ACCOUNTS, "account count");
            for (int i = 0; i < count; i++) {
                Account account = new Account();
                account.name = readText(in, 50, "name");
                account.accountNumber = readText(in, 19, "account number");
                account.balanceCents = in.readLong();
                account.pinSalt = readText(in, 128, "PIN salt");
                account.pinHash = readText(in, 128, "PIN hash");
                account.blocked = in.readBoolean();
                account.transactions = new ArrayList<>();
                int transactionCount = readCount(in, MAX_TRANSACTIONS, "transaction count");
                for (int j = 0; j < transactionCount; j++) account.transactions.add(readText(in, 512, "transaction"));
                validateAccount(account);
                loaded.add(account);
            }
            if (in.read() != -1) throw new IOException("unexpected trailing account data");
        }
        accounts.clear();
        accounts.addAll(loaded);
    }

    private static void importLegacyAccounts() throws IOException, ClassNotFoundException {
        ArrayList<Account> imported = new ArrayList<>();
        try (ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(LEGACY_DATA_FILE)))) {
            in.setObjectInputFilter(legacyInputFilter());
            Object value = in.readObject();
            if (!(value instanceof ArrayList<?>)) throw new IOException("legacy data does not contain an account list");
            for (Object item : (ArrayList<?>) value) {
                if (!(item instanceof Account)) throw new IOException("legacy data contains an unexpected value");
                Account account = (Account) item;
                account.migrateLegacyFields();
                validateAccount(account);
                imported.add(account);
            }
        }
        accounts.clear();
        accounts.addAll(imported);
        if (!saveAccounts()) throw new IOException("could not save migrated account data");
        Files.move(LEGACY_DATA_FILE, LEGACY_DATA_FILE.resolveSibling("accounts.dat.migrated.bak"), StandardCopyOption.REPLACE_EXISTING);
    }

    private static ObjectInputFilter legacyInputFilter() {
        return info -> {
            if (info.depth() > 8 || info.references() > 50_000 || info.arrayLength() > MAX_TRANSACTIONS) return ObjectInputFilter.Status.REJECTED;
            Class<?> type = info.serialClass();
            if (type == null || type == Account.class || type == ArrayList.class || type == String.class || type == Object[].class) return ObjectInputFilter.Status.ALLOWED;
            return ObjectInputFilter.Status.REJECTED;
        };
    }

    private static boolean saveAccounts() {
        try {
            writeAtomically(DATA_FILE, out -> {
                out.writeInt(DATA_MAGIC);
                out.writeInt(DATA_VERSION);
                out.writeInt(accounts.size());
                for (Account account : accounts) {
                    validateAccount(account);
                    writeText(out, account.name);
                    writeText(out, account.accountNumber);
                    out.writeLong(account.balanceCents);
                    writeText(out, account.pinSalt);
                    writeText(out, account.pinHash);
                    out.writeBoolean(account.blocked);
                    out.writeInt(account.transactions.size());
                    for (String transaction : account.transactions) writeText(out, transaction);
                }
            });
            return true;
        } catch (IOException e) {
            error("Could not save account data: " + e.getMessage());
            return false;
        }
    }

    private static void loadAdministrator() {
        if (!Files.exists(ADMIN_FILE)) return;
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(ADMIN_FILE)) {
            properties.load(in);
            String user = properties.getProperty("username");
            String salt = properties.getProperty("salt");
            String hash = properties.getProperty("hash");
            if (user == null || !user.matches("[A-Za-z0-9_.-]{3,32}") || !isBase64(salt, 16) || !isBase64(hash, HASH_BYTES)) {
                throw new IOException("administrator settings are invalid");
            }
            administrator = new AdminCredentials(user, salt, hash);
        } catch (IOException e) {
            adminDataLoadFailed = true;
            System.err.println("Administrator settings were not loaded: " + e.getMessage());
        }
    }

    private static boolean saveAdministrator() {
        Properties properties = new Properties();
        properties.setProperty("username", administrator.username);
        properties.setProperty("salt", administrator.salt);
        properties.setProperty("hash", administrator.hash);
        try {
            writeAtomically(ADMIN_FILE, out -> properties.store(out, "Cypher Bank ATM administrator credential hash"));
            return true;
        } catch (IOException e) {
            error("Could not save administrator settings: " + e.getMessage());
            return false;
        }
    }

    @FunctionalInterface private interface DataWriter { void write(DataOutputStream out) throws IOException; }

    private static void writeAtomically(Path target, DataWriter writer) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path temporary = Files.createTempFile(absolute.getParent(), target.getFileName().toString(), ".tmp");
        try {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) { writer.write(out); }
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void validateAccount(Account account) throws IOException {
        if (account == null || account.name == null || !account.name.matches("[A-Za-z][A-Za-z .'-]{1,49}")
            || account.accountNumber == null || !account.accountNumber.matches("[0-9]{4,19}")
            || account.pinSalt == null || account.pinHash == null || !isBase64(account.pinSalt, 16)
            || !isBase64(account.pinHash, HASH_BYTES) || account.transactions == null
            || account.transactions.size() > MAX_TRANSACTIONS || account.balanceCents < 0) throw new IOException("invalid account record");
    }

    private static void writeText(DataOutputStream out, String value) throws IOException {
        if (value == null || value.length() > 65_535) throw new IOException("invalid text value");
        out.writeUTF(value);
    }

    private static String readText(DataInputStream in, int maxLength, String label) throws IOException {
        String value = in.readUTF();
        if (value.isEmpty() || value.length() > maxLength) throw new IOException("invalid " + label);
        return value;
    }

    private static int readCount(DataInputStream in, int maximum, String label) throws IOException {
        int value = in.readInt();
        if (value < 0 || value > maximum) throw new IOException("invalid " + label);
        return value;
    }

    // ------------------------------------------------------------------
    // DOMAIN, INPUT, AND SECURITY
    // ------------------------------------------------------------------

    private static void seedAccountsIfEmpty() {
        if (!accounts.isEmpty()) return;
        accounts.add(new Account("Abigael Wambia", "1001", "1234", 500_000));
        accounts.add(new Account("Brian Karani", "1002", "2222", 800_000));
        accounts.add(new Account("Mercy Chinwo", "1003", "3333", 300_000));
        saveAccounts();
    }

    private static Account findAccount(String number) {
        for (Account account : accounts) if (account.accountNumber.equals(number)) return account;
        return null;
    }

    private static String generateAccountNumber() {
        long highest = 1000;
        for (Account account : accounts) {
            try { highest = Math.max(highest, Long.parseLong(account.accountNumber)); }
            catch (NumberFormatException ignored) { }
        }
        if (highest >= Long.MAX_VALUE - 1) throw new IllegalStateException("No account numbers are available.");
        return Long.toString(highest + 1);
    }

    private static String readName(String prompt) {
        while (true) {
            String name = readNonEmpty(prompt);
            if (name.matches("[A-Za-z][A-Za-z .'-]{1,49}")) return name;
            error("Enter a name of 2-50 letters (spaces, . ' and - are allowed).");
        }
    }

    private static String readAdminUsername() {
        while (true) {
            String username = readNonEmpty("  Administrator username: ");
            if (username.matches("[A-Za-z0-9_.-]{3,32}")) return username;
            error("Use 3-32 letters, digits, dots, hyphens or underscores.");
        }
    }

    private static String readNewPin() {
        return readPin("  Create a 4-digit PIN: ");
    }

    private static String readPin(String prompt) {
        while (true) {
            char[] characters = readSecret(prompt);
            String pin = new String(characters);
            Arrays.fill(characters, '\0');
            if (pin.matches("\\d{4}")) return pin;
            error("PIN must contain exactly four digits.");
        }
    }

    private static char[] readNewAdminPassword() {
        while (true) {
            char[] password = readSecret("  Create password (12+ characters): ");
            if (password.length >= 12) return password;
            Arrays.fill(password, '\0');
            error("Administrator password must contain at least 12 characters.");
        }
    }

    private static char[] readSecret(String prompt) {
        Console console = System.console();
        animatedPrompt(prompt);
        if (console != null) {
            char[] value = console.readPassword("");
            return value == null ? new char[0] : value;
        }
        // An IDE/build console may not offer character masking.
        return scanner.nextLine().toCharArray();
    }

    private static long readPositiveAmount(String prompt) {
        while (true) {
            long amount = readAmount(prompt);
            if (amount > 0) return amount;
            error("Amount must be greater than zero.");
        }
    }

    private static long readNonNegativeAmount(String prompt) {
        while (true) {
            long amount = readAmount(prompt);
            if (amount >= 0) return amount;
            error("Amount cannot be negative.");
        }
    }

    private static long readAmount(String prompt) {
        while (true) {
            try { return toCents(new BigDecimal(readNonEmpty(prompt).replace(",", ""))); }
            catch (NumberFormatException | ArithmeticException e) { error("Enter an amount with no more than two decimal places, e.g. 2500.50."); }
        }
    }

    private static long toCents(BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    private static int readInt(String prompt, int min, int max) {
        while (true) {
            try {
                int value = Integer.parseInt(readNonEmpty(prompt));
                if (value >= min && value <= max) return value;
            } catch (NumberFormatException ignored) { }
            error("Enter a number from " + min + " to " + max + ".");
        }
    }

    private static String readNonEmpty(String prompt) {
        while (true) {
            animatedPrompt(prompt);
            String value = scanner.nextLine().trim();
            if (!value.isEmpty()) return value;
            error("This field cannot be empty.");
        }
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static String hashSecret(char[] secret, byte[] salt) {
        PBEKeySpec specification = new PBEKeySpec(secret, salt, HASH_ITERATIONS, HASH_BYTES * 8);
        try {
            return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).getEncoded());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Secure password hashing is unavailable.", e);
        } finally {
            specification.clearPassword();
        }
    }

    private static boolean verifySecret(char[] candidate, String salt, String expectedHash) {
        try {
            byte[] expected = Base64.getDecoder().decode(expectedHash);
            byte[] actual = Base64.getDecoder().decode(hashSecret(candidate, Base64.getDecoder().decode(salt)));
            return MessageDigest.isEqual(actual, expected);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isBase64(String value, int expectedBytes) {
        try { return Base64.getDecoder().decode(value).length == expectedBytes; }
        catch (IllegalArgumentException e) { return false; }
    }

    // ------------------------------------------------------------------
    // DISPLAY, ANIMATION, AND AUDIO
    // ------------------------------------------------------------------

    private static String money(long cents) {
        return String.format(Locale.US, "KSh %,.2f", cents / 100.0d);
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 3)) + "...";
    }

    private static void header(String title) {
        panelBorder();
        panelLine("  " + BANK_NAME + " / SELF-SERVICE ATM");
        panelLine("  " + title.toUpperCase(Locale.ROOT));
        panelLine("  " + TERMINAL_ID + " / " + TERMINAL_LOCATION);
        panelLine("  SECURE SESSION - FOLLOW ON-SCREEN INSTRUCTIONS");
        panelBorder();
    }

    private static void menuItem(int number, String text) {
        System.out.printf("%s%s%s  [ %02d ]  %-36s%s%n", NAVY, CYAN, BOLD,
            number, text.toUpperCase(Locale.ROOT), RESET);
    }

    private static void footer() {
        System.out.println(NAVY + WHITE + "  ------------------------------------------------" + RESET);
        animatedInstruction("CHOOSE A NUMBER, THEN PRESS ENTER");
    }

    private static void line() {
        System.out.println(NAVY + WHITE + "  ------------------------------------------------" + RESET);
    }

    private static void receiptLine(String label, String value) {
        String body = label.isEmpty() ? shorten(value, 46) : shorten(label + ": " + value, 46);
        System.out.printf("|  %-46s  |%n", body);
    }

    private static void atmSequence(String message, int stages, int delay, Sound sound) {
        String[] frames = {"[>       ]", "[==>     ]", "[====>   ]", "[======> ]", "[========]"};
        playSound(sound);
        for (int i = 0; i < stages; i++) {
            System.out.print("\r" + NAVY + YELLOW + BOLD + "  " + message + " "
                + frames[i % frames.length] + RESET);
            pause(delay);
        }
        System.out.print("\r" + NAVY + GREEN + BOLD + "  " + message
            + " [COMPLETE]" + RESET + "\n");
    }

    private static void success(String message) { System.out.println(NAVY + GREEN + BOLD + "\n  OK  " + message.toUpperCase(Locale.ROOT) + RESET); playSound(Sound.ACCEPT); }
    private static void error(String message) { System.out.println(NAVY + RED + BOLD + "\n  ERROR  " + message.toUpperCase(Locale.ROOT) + RESET); playSound(Sound.ERROR); }
    private static void warning(String message) { System.out.println(NAVY + YELLOW + BOLD + "\n  NOTICE  " + message.toUpperCase(Locale.ROOT) + RESET); }

    private static void pausePrompt() { animatedInstruction("PRESS ENTER TO CONTINUE"); scanner.nextLine(); }
    private static void pause(long milliseconds) {
        try { Thread.sleep(milliseconds); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void clearScreen() {
        if (ANSI) System.out.print("\033[H\033[2J");
        else for (int i = 0; i < 28; i++) System.out.println();
        System.out.flush();
    }

    private static String color(String code) { return ANSI ? "\u001B[" + code + "m" : ""; }

    private static boolean hasProgramFlag(String flag) {
        return Arrays.asList(System.getProperty("sun.java.command", "").split("\\s+"))
            .contains(flag);
    }

    private static void panelLine(String text) {
        System.out.printf("%s%s%s| %-48s |%s%n", NAVY, WHITE, BOLD,
            shorten(text, 48), RESET);
    }

    private static void panelBorder() {
        System.out.println(NAVY + WHITE + BOLD
            + "+--------------------------------------------------+" + RESET);
    }

    private static void animatedInstruction(String text) {
        String message = text.toUpperCase(Locale.ROOT);
        System.out.print(NAVY + YELLOW + BOLD + "  >> ");
        if (ANIMATIONS_ENABLED) {
            for (int i = 0; i < message.length(); i++) {
                System.out.print(message.charAt(i));
                System.out.flush();
                pause(TYPEWRITER_DELAY_MS);
            }
        } else {
            System.out.print(message);
        }
        System.out.println(RESET);
    }

    private static void animatedPrompt(String prompt) {
        String label = prompt.trim().replaceAll(":$", "").toUpperCase(Locale.ROOT);
        System.out.print(NAVY + CYAN + BOLD + "  >> ");
        if (ANIMATIONS_ENABLED) {
            for (int i = 0; i < label.length(); i++) {
                System.out.print(label.charAt(i));
                System.out.flush();
                pause(TYPEWRITER_DELAY_MS);
            }
        } else {
            System.out.print(label);
        }
        System.out.print(WHITE + " : " + RESET);
    }

    private static void playSound(Sound sound) {
        if (!SOUND_ENABLED) return;
        Thread player = new Thread(() -> playTonePattern(sound), "atm-tone");
        player.setDaemon(true);
        player.start();
    }

    private static void playTonePattern(Sound sound) {
        int[] notes;
        int duration;
        switch (sound) {
            case START: notes = new int[] {660, 880}; duration = 85; break;
            case ACCEPT: notes = new int[] {880, 1100}; duration = 65; break;
            case DISPENSE: notes = new int[] {440, 520, 660}; duration = 55; break;
            case ERROR: notes = new int[] {240, 180}; duration = 120; break;
            default: return;
        }
        try { for (int note : notes) playTone(note, duration); }
        catch (LineUnavailableException | IllegalArgumentException ignored) { /* Sound is optional. */ }
    }

    private static void playTone(int hertz, int milliseconds) throws LineUnavailableException {
        final float sampleRate = 8_000f;
        int samples = (int) (sampleRate * milliseconds / 1_000);
        byte[] buffer = new byte[samples];
        for (int i = 0; i < samples; i++) buffer[i] = (byte) (Math.sin(2.0 * Math.PI * i * hertz / sampleRate) * 50);
        AudioFormat format = new AudioFormat(sampleRate, 8, 1, true, false);
        try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
            line.open(format, buffer.length);
            line.start();
            line.write(buffer, 0, buffer.length);
            line.drain();
        }
    }

    private static void goodbye() {
        clearScreen();
        header("SESSION COMPLETE");
        atmSequence("SECURING SESSION", 4, 75, Sound.ACCEPT);
        System.out.println(GREEN + BOLD + "\n  Thank you for banking with " + BANK_NAME + "." + RESET);
        System.out.println(CYAN + "  Please take your card and receipt." + RESET);
    }

    private static String maskAccount(String accountNumber) {
        int visible = Math.min(4, accountNumber.length());
        return "**** " + accountNumber.substring(accountNumber.length() - visible);
    }

    private static String transactionReference() {
        return "CB" + new SimpleDateFormat("yyMMddHHmmssSSS").format(new Date());
    }
}
