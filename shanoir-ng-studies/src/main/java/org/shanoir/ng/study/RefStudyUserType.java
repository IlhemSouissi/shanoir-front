package org.shanoir.ng.study;

import java.io.Serializable;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Transient;

/**
 * The class RefStudyUserType.
 *
 */
@Entity
@Table(name = "REF_STUDY_USER_TYPE")
public class RefStudyUserType implements Serializable, Comparable<RefStudyUserType> {

	/**
	 * UID
	 */
	private static final long serialVersionUID = -9071565964737166885L;

	/** The id. */
	@Id
	// @GeneratedValue(strategy = GenerationType.AUTO)
	@Column(name = "REF_STUDY_USER_TYPE_ID")
	private Long id;

	/** The label name. */
	@Column(name = "LABEL_NAME", nullable = false, unique = true)
	private String labelName;

	/**
	 * Creates a new RefStudyUserType object.
	 *
	 * @param labelName
	 *            the label name
	 */
	public RefStudyUserType(final String labelName) {
		super();
		this.labelName = labelName;
	}

	/**
	 * Creates a new RefStudyUserType object.
	 */
	public RefStudyUserType() {
		super();
	}

	/**
	 * Gets the id.
	 *
	 * @return the id
	 */
	/*
	 * @XmlID
	 * 
	 * @XmlJavaTypeAdapter(value=LongAdapter.class, type=String.class) public
	 * Long getId() { return id; }
	 */

	/**
	 * Sets the id.
	 *
	 * @param id
	 *            the id
	 */
	public void setId(final Long id) {
		this.id = id;
	}

	/**
	 * Gets the label name.
	 *
	 * @return the label name
	 */
	public String getLabelName() {
		return labelName;
	}

	/**
	 * Sets the label name.
	 *
	 * @param labelName
	 *            the label name
	 */
	public void setLabelName(final String labelName) {
		this.labelName = labelName;
	}

	/**
	 * Hash code.
	 *
	 * @return the result
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = (prime * result) + ((labelName == null) ? 0 : labelName.hashCode());

		return result;
	}

	/**
	 * Equals.
	 *
	 * @param obj
	 *            obj
	 *
	 * @return true, if equals
	 */
	@Override
	public boolean equals(final Object obj) {
		if (this == obj) {
			return true;
		}

		if (obj == null) {
			return false;
		}

		if (obj instanceof RefStudyUserType) {
			final RefStudyUserType other = (RefStudyUserType) obj;

			boolean isEqual = true;

			if (getLabelName() != null) {
				isEqual &= getLabelName().equals(other.getLabelName());
			}

			return isEqual;
		}

		return false;
	}

	/**
	 * Gets the String.
	 *
	 * @return the String
	 */
	@Override
	public String toString() {
		return labelName;
	}

	/**
	 * Compare to.
	 *
	 * @param other
	 *            the other
	 *
	 * @return the int
	 */
	public int compareTo(final RefStudyUserType other) {
		return this.toString().toUpperCase().compareTo(other.toString().toUpperCase());
	}

	/**
	 * Gets the display String.
	 *
	 * @return the String
	 */
	@Transient
	public String getDisplayString() {
		return toString();
	}

	/**
	 * Tells whether we can edit the references.
	 *
	 * @return true or false;
	 */
	/*
	 * @Transient public boolean isEditable() { String refClassName =
	 * this.getClass().getName(); refClassName =
	 * refClassName.substring(refClassName.lastIndexOf('.') + 1); refClassName =
	 * refClassName.toUpperCase(); Integer n =
	 * ShanoirUtil.getShanoirRefSizeMap().get(refClassName);
	 * 
	 * if (n == null || getId() > n) { return true; } return false; }
	 */
}
