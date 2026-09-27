import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logs in to MySQL with the server's settings and creates the server's database, so the start
 * script can tell a wrong password or a stopped MySQL apart before starting anything. The database
 * is created with utf8mb4 explicitly: MySQL 5.7 and MariaDB default to latin1, which cannot hold
 * the Chinese text the controller stores. Reads the connection from the server's environment
 * variables, which keeps the password off the command line. Prints one line:
 * "OK version", "CHARSET name" (exit code 2) or "ERROR code sqlState message" (exit code 1).
 */
public class DbCheck {
    private static final Pattern URL = Pattern.compile("(jdbc:mysql://[^/?]+/)([A-Za-z0-9_]+)(\\?.*)?");

    public static void main(String[] args) {
        Matcher url = URL.matcher(String.valueOf(System.getenv("SPRING_DATASOURCE_URL")));
        if (!url.matches()) {
            System.out.println("ERROR 0 - SPRING_DATASOURCE_URL is not jdbc:mysql://host:port/database");
            System.exit(1);
        }
        String database = url.group(2);
        String server = url.group(1) + (url.group(3) == null ? "" : url.group(3));
        try (Connection connection = DriverManager.getConnection(server, System.getenv("MYSQL_USERNAME"), System.getenv("MYSQL_PASSWORD"))) {
            connection.createStatement().execute("CREATE DATABASE IF NOT EXISTS `" + database + "` DEFAULT CHARACTER SET utf8mb4");
            PreparedStatement query = connection.prepareStatement(
                    "SELECT DEFAULT_CHARACTER_SET_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?");
            query.setString(1, database);
            ResultSet charset = query.executeQuery();
            charset.next();
            if (!"utf8mb4".equalsIgnoreCase(charset.getString(1))) {
                System.out.println("CHARSET " + charset.getString(1));
                System.exit(2);
            }
            System.out.println("OK " + connection.getMetaData().getDatabaseProductVersion());
        } catch (SQLException e) {
            String message = String.valueOf(e.getMessage()).replaceAll("\\s+", " ");
            System.out.println("ERROR " + e.getErrorCode() + " " + e.getSQLState() + " " + message);
            System.exit(1);
        }
    }
}
