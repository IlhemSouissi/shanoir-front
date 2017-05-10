package org.shanoir.ng.center;

import java.util.List;

import javax.validation.Valid;

import org.shanoir.ng.shared.error.FieldErrorMap;
import org.shanoir.ng.shared.exception.ErrorDetails;
import org.shanoir.ng.shared.exception.ErrorModel;
import org.shanoir.ng.shared.exception.RestServiceException;
import org.shanoir.ng.shared.exception.ShanoirStudiesException;
import org.shanoir.ng.shared.validation.EditableOnlyByValidator;
import org.shanoir.ng.shared.validation.UniqueValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import io.swagger.annotations.ApiParam;

@Controller
public class CenterApiController implements CenterApi {

	private static final Logger LOG = LoggerFactory.getLogger(CenterApiController.class);

	@Autowired
	private CenterService centerService;

	@Override
	public ResponseEntity<Void> deleteCenter(
			@ApiParam(value = "id of the center", required = true) @PathVariable("centerId") final Long centerId) {
		if (centerService.findById(centerId) == null) {
			return new ResponseEntity<Void>(HttpStatus.NOT_FOUND);
		}
		try {
			centerService.deleteById(centerId);
		} catch (ShanoirStudiesException e) {
			return new ResponseEntity<Void>(HttpStatus.INTERNAL_SERVER_ERROR);
		}
		return new ResponseEntity<Void>(HttpStatus.NO_CONTENT);
	}

	@Override
	public ResponseEntity<Center> findCenterById(
			@ApiParam(value = "id of the center", required = true) @PathVariable("centerId") final Long centerId) {
		final Center center = centerService.findById(centerId);
		if (center == null) {
			return new ResponseEntity<Center>(HttpStatus.NOT_FOUND);
		}
		return new ResponseEntity<Center>(center, HttpStatus.OK);
	}

	@Override
	public ResponseEntity<List<Center>> findCenters() {
		final List<Center> centers = centerService.findAll();
		if (centers.isEmpty()) {
			return new ResponseEntity<List<Center>>(HttpStatus.NO_CONTENT);
		}
		return new ResponseEntity<List<Center>>(centers, HttpStatus.OK);
	}

	@Override
	public ResponseEntity<Center> saveNewCenter(
			@ApiParam(value = "the center to create", required = true) @RequestBody @Valid final Center center,
			final BindingResult result) throws RestServiceException {

		/* Validation */
		// A basic user can only update certain fields, check that
		final FieldErrorMap accessErrors = this.getCreationRightsErrors(center);
		// Check hibernate validation
		final FieldErrorMap hibernateErrors = new FieldErrorMap(result);
		// Check unique constrainte
		final FieldErrorMap uniqueErrors = this.getUniqueConstraintErrors(center);
		/* Merge errors. */
		final FieldErrorMap errors = new FieldErrorMap(accessErrors, hibernateErrors, uniqueErrors);
		if (!errors.isEmpty()) {
			throw new RestServiceException(
					new ErrorModel(HttpStatus.UNPROCESSABLE_ENTITY.value(), "Bad arguments", new ErrorDetails(errors)));
		}

		// Guarantees it is a creation, not an update
		center.setId(null);

		/* Save center in db. */
		try {
			final Center createdCenter = centerService.save(center);
			return new ResponseEntity<Center>(createdCenter, HttpStatus.OK);
		} catch (ShanoirStudiesException e) {
			throw new RestServiceException(
					new ErrorModel(HttpStatus.UNPROCESSABLE_ENTITY.value(), "Bad arguments", null));
		}
	}

	@Override
	public ResponseEntity<Void> updateCenter(
			@ApiParam(value = "id of the center", required = true) @PathVariable("centerId") final Long centerId,
			@ApiParam(value = "the center to update", required = true) @RequestBody @Valid final Center center,
			final BindingResult result) throws RestServiceException {

		// IMPORTANT : avoid any confusion that could lead to security breach
		center.setId(centerId);

		// A basic center can only update certain fields, check that
		final FieldErrorMap accessErrors = this.getUpdateRightsErrors(center);
		// Check hibernate validation
		final FieldErrorMap hibernateErrors = new FieldErrorMap(result);
		// Check unique constrainte
		final FieldErrorMap uniqueErrors = this.getUniqueConstraintErrors(center);
		/* Merge errors. */
		final FieldErrorMap errors = new FieldErrorMap(accessErrors, hibernateErrors, uniqueErrors);
		if (!errors.isEmpty()) {
			throw new RestServiceException(
					new ErrorModel(HttpStatus.UNPROCESSABLE_ENTITY.value(), "Bad arguments", new ErrorDetails(errors)));
		}

		/* Update center in db. */
		try {
			centerService.update(center);
		} catch (ShanoirStudiesException e) {
			LOG.error("Error while trying to update center " + centerId + " : ", e);
			throw new RestServiceException(
					new ErrorModel(HttpStatus.UNPROCESSABLE_ENTITY.value(), "Bad arguments", null));
		}

		return new ResponseEntity<Void>(HttpStatus.NO_CONTENT);
	}

	/*
	 * Get access rights errors.
	 *
	 * @param center center.
	 * 
	 * @return an error map.
	 */
	private FieldErrorMap getUpdateRightsErrors(final Center center) {
		final Center previousStatecenter = centerService.findById(center.getId());
		final FieldErrorMap accessErrors = new EditableOnlyByValidator<Center>().validate(previousStatecenter, center);
		return accessErrors;
	}

	/*
	 * Get access rights errors.
	 *
	 * @param center center.
	 * 
	 * @return an error map.
	 */
	private FieldErrorMap getCreationRightsErrors(final Center center) {
		return new EditableOnlyByValidator<Center>().validate(center);
	}

	/*
	 * Get unique constraint errors
	 *
	 * @param center
	 * 
	 * @return an error map
	 */
	private FieldErrorMap getUniqueConstraintErrors(final Center center) {
		final UniqueValidator<Center> uniqueValidator = new UniqueValidator<Center>(centerService);
		final FieldErrorMap uniqueErrors = uniqueValidator.validate(center);
		return uniqueErrors;
	}

}
