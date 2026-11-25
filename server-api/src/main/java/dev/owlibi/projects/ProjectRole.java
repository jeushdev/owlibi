package dev.owlibi.projects;

/**
 * A person's role in one project. The names match the {@code project_memberships.role} column.
 *
 * <p>Each project has exactly one leader, who plans tasks and approves work. Everyone else who joins
 * through an invitation is a member.
 */
public enum ProjectRole {
    LEADER,
    MEMBER;

    /** Whether this role may manage the project: edit tasks, rules and invitations. */
    public boolean isLeader() {
        return this == LEADER;
    }

    /**
     * The role stored under {@code value}.
     *
     * @throws IllegalArgumentException when {@code value} is not a known role
     */
    public static ProjectRole from(String value) {
        for (ProjectRole role : values()) {
            if (role.name().equals(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown project role: " + value);
    }
}
